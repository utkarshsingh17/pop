package ai.utkarsh.pop.infrastructure.investigator.postgres;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.application.tool.InvestigationContext;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.infrastructure.config.InvestigationProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives the investigator against a real Postgres carrying a real, deliberately introduced
 * performance problem: a large table with no index on the column every query filters by.
 *
 * <p>This is the check that matters — unit tests with a mocked JdbcTemplate would only prove
 * the SQL strings are unchanged, not that they actually detect anything.
 */
class PostgresInvestigatorIntegrationTest {

    private static final ServiceName SERVICE = ServiceName.of("order-service");

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("shop")
                    .withUsername("pop")
                    .withPassword("pop")
                    // pg_stat_statements only collects when preloaded at server start.
                    .withCommand("postgres",
                            "-c", "shared_preload_libraries=pg_stat_statements",
                            "-c", "pg_stat_statements.track=all");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PostgresInvestigator investigator;
    private static SqlAnalysisService analysis;
    private static InvestigationContext context;

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(3);

        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");

        seedSlowScenario();

        Clock clock = Clock.fixed(Instant.parse("2026-08-22T10:00:00Z"), ZoneOffset.UTC);
        context = new InvestigationContext();
        // The resolver seam: this test pins every service to the one container it started,
        // which is exactly what the registry does for an unregistered service.
        investigator = new PostgresInvestigator(service -> jdbc, clock);
        analysis = new SqlAnalysisService(service -> jdbc, context, new SqlSafetyGuard(),
                new InvestigationProperties(Duration.ofHours(1), false));
    }

    @AfterAll
    static void stopDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
        POSTGRES.stop();
    }

    /** A table large enough that a sequential scan is genuinely expensive, with no index. */
    private static void seedSlowScenario() {
        jdbc.execute("""
                CREATE TABLE orders (
                    id          BIGSERIAL PRIMARY KEY,
                    customer_id BIGINT      NOT NULL,
                    status      VARCHAR(32) NOT NULL,
                    total_cents BIGINT      NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
        jdbc.update("""
                INSERT INTO orders (customer_id, status, total_cents)
                SELECT (random() * 4999 + 1)::BIGINT,
                       'PAID',
                       (random() * 50000)::BIGINT
                FROM generate_series(1, 60000)
                """);
        jdbc.execute("ANALYZE orders");

        jdbc.execute("SELECT pg_stat_statements_reset()");

        // Postgres buffers table statistics per backend and flushes them on a timer, so the
        // scans must be driven and flushed on ONE connection or pg_stat_user_tables still
        // reads zero. pg_stat_force_next_flush() only flushes the calling backend's buffer.
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (int i = 0; i < 30; i++) {
                statement.execute("SELECT count(*) FROM orders WHERE customer_id = " + (i + 1));
            }
            statement.execute("SELECT pg_stat_force_next_flush()");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("failed to seed scan statistics", e);
        }

        awaitSequentialScanStatistics();
        jdbc.execute("ANALYZE orders");
    }

    /** Stats become visible asynchronously; poll briefly rather than sleeping a fixed time. */
    private static void awaitSequentialScanStatistics() {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            Long seqScan = jdbc.queryForObject(
                    "SELECT COALESCE(seq_scan, 0) FROM pg_stat_user_tables WHERE relname = 'orders'",
                    Long.class);
            if (seqScan != null && seqScan > 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("pg_stat_user_tables never reported the seeded sequential scans");
    }

    private static List<Finding> investigate() {
        return investigator.investigate(SERVICE, TimeRange.lastly(Duration.ofHours(1), Instant.now()));
    }

    @Test
    void investigate_shouldDetectSequentialScansOnUnindexedTable() {
        List<Finding> findings = investigate();

        assertThat(findings)
                .filteredOn(f -> f.title().contains("scanned sequentially"))
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.source()).isEqualTo(FindingSource.POSTGRES);
                    assertThat(f.evidence()).containsEntry("table", "orders");
                    assertThat(f.severity().isAtLeast(Severity.MEDIUM)).isTrue();
                });
    }

    @Test
    void investigate_shouldReportEveryFindingAgainstThePostgresSource() {
        assertThat(investigate())
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.source()).isEqualTo(FindingSource.POSTGRES));
    }

    @Test
    void investigate_shouldNotThrowWhenACheckIsUnavailable() {
        // A JdbcTemplate pointed at a closed pool makes every check fail; the investigator
        // must degrade to informational findings rather than blowing up the investigation.
        HikariDataSource broken = new HikariDataSource();
        broken.setJdbcUrl(POSTGRES.getJdbcUrl());
        broken.setUsername("nobody");
        broken.setPassword("wrong");
        broken.setConnectionTimeout(1_000);
        broken.setInitializationFailTimeout(-1);

        JdbcTemplate brokenTemplate = new JdbcTemplate(broken);
        PostgresInvestigator failing =
                new PostgresInvestigator(service -> brokenTemplate, Clock.systemUTC());
        List<Finding> findings = failing.investigate(SERVICE, TimeRange.lastly(Duration.ofHours(1), Instant.now()));

        assertThat(findings)
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.INFO))
                .allSatisfy(f -> assertThat(f.title()).startsWith("Check unavailable:"));
        broken.close();
    }

    @Test
    void explain_shouldReturnAPlanShowingTheSequentialScan() {
        // SqlAnalysisService resolves its database from the investigation bound to the thread,
        // so the call has to run inside one — the same path the agent's tools take.
        String plan = withInvestigation(
                () -> analysis.explain("SELECT count(*) FROM orders WHERE customer_id = 42"));

        assertThat(plan).containsIgnoringCase("Seq Scan on orders");
    }

    /** Binds an investigation for calls that resolve their target from the thread. */
    private static <T> T withInvestigation(java.util.function.Supplier<T> action) {
        Investigation investigation = Investigation.open(
                "why is it slow?", SERVICE,
                TimeRange.lastly(Duration.ofHours(1), Instant.now()), Instant.now());
        return context.runWithin(investigation, action);
    }

    @Test
    void explain_shouldRejectAWriteStatement() {
        assertThatThrownBy(() -> withInvestigation(() -> analysis.explain("DELETE FROM orders")))
                .isInstanceOf(UnsafeSqlException.class);
    }

    @Test
    void explain_shouldRejectExplainAnalyzeWhenDisabled() {
        assertThatThrownBy(() -> withInvestigation(
                () -> analysis.explain("EXPLAIN ANALYZE SELECT * FROM orders")))
                .isInstanceOf(UnsafeSqlException.class)
                .hasMessageContaining("executes the statement");
    }

    @Test
    void tableInfo_shouldReportRowsAndScanCounts() {
        SqlAnalysisService.TableInfo info = withInvestigation(() -> analysis.tableInfo("orders"));

        assertThat(info.name()).isEqualTo("orders");
        assertThat(info.estimatedRows()).isGreaterThan(50_000);
        assertThat(info.seqScan()).isPositive();
    }

    @Test
    void tableInfo_shouldRejectNonIdentifierInput() {
        assertThatThrownBy(() -> withInvestigation(() -> analysis.tableInfo("orders; DROP TABLE orders")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tableInfo_whenTableMissing_shouldThrow() {
        assertThatThrownBy(() -> withInvestigation(() -> analysis.tableInfo("no_such_table")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such table");
    }

    @Test
    void listTables_shouldIncludeSeededTable() {
        assertThat(withInvestigation(() -> analysis.listTables())).contains("orders");
    }

    @Test
    void indexesOf_shouldReportThePrimaryKeyOnly() {
        List<SqlAnalysisService.IndexInfo> indexes = withInvestigation(() -> analysis.indexesOf("orders"));

        assertThat(indexes).hasSize(1);
        assertThat(indexes.getFirst().definition()).contains("(id)");
    }

    @Test
    void suggestIndexes_shouldProposeTheMissingForeignKeyIndex() {
        List<String> suggestions = withInvestigation(() -> analysis.suggestIndexes("orders"));

        assertThat(suggestions)
                .anySatisfy(s -> assertThat(s).contains("customer_id").contains("CREATE INDEX"));
    }

    @Test
    void columnsOf_shouldDescribeEveryColumn() {
        List<SqlAnalysisService.ColumnInfo> columns = withInvestigation(() -> analysis.columnsOf("orders"));

        assertThat(columns).extracting(SqlAnalysisService.ColumnInfo::name)
                .containsExactly("id", "customer_id", "status", "total_cents", "created_at");
    }
}
