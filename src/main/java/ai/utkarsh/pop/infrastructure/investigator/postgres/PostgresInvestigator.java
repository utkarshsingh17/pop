package ai.utkarsh.pop.infrastructure.investigator.postgres;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import ai.utkarsh.pop.infrastructure.config.TargetDatabaseResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Driven adapter: gathers evidence from the Postgres instance under observation.
 *
 * <p>Every check is read-only and bounded. Individual checks are isolated from each other —
 * a missing {@code pg_stat_statements} extension or a permissions gap degrades that one check
 * into an informational finding rather than failing the whole investigation, because partial
 * evidence is still worth reasoning over.
 */
@Slf4j
@Component
public class PostgresInvestigator implements InvestigatorPort {

    /** A statement averaging longer than this is worth surfacing. */
    private static final double SLOW_QUERY_MEAN_MS = 100.0;

    /** Connection-pool utilisation above this is treated as saturation. */
    private static final double CONNECTION_SATURATION_RATIO = 0.80;

    /** Below this live-row count a sequential scan is cheap and uninteresting. */
    private static final long SEQ_SCAN_MIN_ROWS = 10_000L;

    /** Dead-tuple fraction above which autovacuum is plainly not keeping up. */
    private static final double DEAD_TUPLE_RATIO = 0.20;

    private final TargetDatabaseResolver targets;
    private final Clock clock;

    PostgresInvestigator(TargetDatabaseResolver targets, Clock clock) {
        this.targets = targets;
        this.clock = clock;
    }

    @Override
    public FindingSource source() {
        return FindingSource.POSTGRES;
    }

    @Override
    public List<Finding> investigate(ServiceName service, TimeRange range) {
        List<Finding> findings = new ArrayList<>();
        // Resolved once per sweep rather than held as a field: which database this investigator
        // talks to now depends on which service is being investigated.
        JdbcTemplate jdbc = targets.jdbcFor(service);
        findings.addAll(safely("slow queries", () -> slowQueries(jdbc)));
        findings.addAll(safely("connection saturation", () -> connectionSaturation(jdbc)));
        findings.addAll(safely("lock waits", () -> lockWaits(jdbc)));
        findings.addAll(safely("idle transactions", () -> idleInTransaction(jdbc)));
        findings.addAll(safely("sequential scans", () -> sequentialScans(jdbc)));
        findings.addAll(safely("table bloat", () -> tableBloat(jdbc)));
        return findings;
    }

    /**
     * Runs one check, converting any failure into an informational finding.
     *
     * <p>A dead check is itself diagnostic information — "pg_stat_statements is not installed"
     * is something the operator wants to know — so it is reported rather than swallowed.
     */
    private List<Finding> safely(String checkName, Supplier<List<Finding>> check) {
        try {
            return check.get();
        } catch (RuntimeException e) {
            log.warn("Postgres check '{}' failed: {}", checkName, e.getMessage());
            return List.of(Finding.of(FindingSource.POSTGRES, Severity.INFO,
                    "Check unavailable: " + checkName,
                    "This check could not run: " + e.getMessage()
                            + ". Treat its absence as unknown, not as healthy.",
                    now(), Finding.evidenceOf("check", checkName, "error", String.valueOf(e.getMessage()))));
        }
    }

    private List<Finding> slowQueries(JdbcTemplate jdbc) {
        String sql = """
                SELECT query,
                       calls,
                       round(mean_exec_time::numeric, 2)  AS mean_ms,
                       round(total_exec_time::numeric, 2) AS total_ms,
                       rows
                FROM pg_stat_statements
                WHERE mean_exec_time > ?
                ORDER BY total_exec_time DESC
                LIMIT 10
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            double meanMs = rs.getDouble("mean_ms");
            long calls = rs.getLong("calls");
            String query = normalise(rs.getString("query"));

            return Finding.of(FindingSource.POSTGRES, severityForLatency(meanMs),
                    "Slow query averaging %.0f ms over %d calls".formatted(meanMs, calls),
                    "Statement: " + query,
                    now(),
                    Finding.evidenceOf(
                            "query", query,
                            "calls", String.valueOf(calls),
                            "mean_ms", String.valueOf(meanMs),
                            "total_ms", rs.getString("total_ms"),
                            "rows", rs.getString("rows")));
        }, SLOW_QUERY_MEAN_MS);
    }

    private List<Finding> connectionSaturation(JdbcTemplate jdbc) {
        String sql = """
                SELECT (SELECT count(*) FROM pg_stat_activity)                              AS used,
                       (SELECT setting::int FROM pg_settings WHERE name = 'max_connections') AS max
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            int used = rs.getInt("used");
            int max = rs.getInt("max");
            double ratio = max == 0 ? 0 : (double) used / max;
            if (ratio < CONNECTION_SATURATION_RATIO) {
                return null;
            }
            return Finding.of(FindingSource.POSTGRES,
                    ratio >= 0.95 ? Severity.CRITICAL : Severity.HIGH,
                    "Connection pool %.0f%% saturated (%d/%d)".formatted(ratio * 100, used, max),
                    "New connections will be refused once max_connections is reached.",
                    now(),
                    Finding.evidenceOf("used", String.valueOf(used), "max", String.valueOf(max)));
        }).stream().filter(java.util.Objects::nonNull).toList();
    }

    private List<Finding> lockWaits(JdbcTemplate jdbc) {
        String sql = """
                SELECT blocked.pid                                    AS blocked_pid,
                       blocked.query                                  AS blocked_query,
                       blocking.pid                                   AS blocking_pid,
                       blocking.query                                 AS blocking_query,
                       EXTRACT(EPOCH FROM (now() - blocked.query_start)) AS waited_seconds
                FROM pg_stat_activity blocked
                JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS blocking_pid ON TRUE
                JOIN pg_stat_activity blocking ON blocking.pid = blocking_pid
                WHERE cardinality(pg_blocking_pids(blocked.pid)) > 0
                LIMIT 10
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            double waited = rs.getDouble("waited_seconds");
            return Finding.of(FindingSource.POSTGRES,
                    waited > 30 ? Severity.CRITICAL : Severity.HIGH,
                    "Query blocked for %.0f s waiting on another session".formatted(waited),
                    "Blocked: " + normalise(rs.getString("blocked_query"))
                            + " | Blocking: " + normalise(rs.getString("blocking_query")),
                    now(),
                    Finding.evidenceOf(
                            "blocked_pid", rs.getString("blocked_pid"),
                            "blocking_pid", rs.getString("blocking_pid"),
                            "waited_seconds", String.valueOf(waited)));
        });
    }

    private List<Finding> idleInTransaction(JdbcTemplate jdbc) {
        String sql = """
                SELECT pid,
                       query,
                       EXTRACT(EPOCH FROM (now() - state_change)) AS idle_seconds
                FROM pg_stat_activity
                WHERE state = 'idle in transaction'
                  AND state_change < now() - INTERVAL '60 seconds'
                ORDER BY state_change
                LIMIT 10
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            double idle = rs.getDouble("idle_seconds");
            return Finding.of(FindingSource.POSTGRES, Severity.MEDIUM,
                    "Session idle in transaction for %.0f s".formatted(idle),
                    "Holds locks and prevents vacuum from reclaiming rows. Last statement: "
                            + normalise(rs.getString("query")),
                    now(),
                    Finding.evidenceOf("pid", rs.getString("pid"), "idle_seconds", String.valueOf(idle)));
        });
    }

    /** Sequential scans over large tables — the signature of a missing index. */
    private List<Finding> sequentialScans(JdbcTemplate jdbc) {
        String sql = """
                SELECT relname,
                       seq_scan,
                       COALESCE(idx_scan, 0) AS idx_scan,
                       n_live_tup
                FROM pg_stat_user_tables
                WHERE seq_scan > 0
                  AND n_live_tup > ?
                  AND seq_scan > COALESCE(idx_scan, 0)
                ORDER BY seq_scan * n_live_tup DESC
                LIMIT 10
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            String table = rs.getString("relname");
            long seqScan = rs.getLong("seq_scan");
            long idxScan = rs.getLong("idx_scan");
            long liveTuples = rs.getLong("n_live_tup");

            return Finding.of(FindingSource.POSTGRES,
                    idxScan == 0 ? Severity.HIGH : Severity.MEDIUM,
                    "Table '%s' is scanned sequentially (%d seq scans vs %d index scans over %d rows)"
                            .formatted(table, seqScan, idxScan, liveTuples),
                    "Sequential scans dominate access to this table, which usually means a "
                            + "predicate has no supporting index.",
                    now(),
                    Finding.evidenceOf(
                            "table", table,
                            "seq_scan", String.valueOf(seqScan),
                            "idx_scan", String.valueOf(idxScan),
                            "n_live_tup", String.valueOf(liveTuples)));
        }, SEQ_SCAN_MIN_ROWS);
    }

    private List<Finding> tableBloat(JdbcTemplate jdbc) {
        String sql = """
                SELECT relname,
                       n_live_tup,
                       n_dead_tup,
                       last_autovacuum
                FROM pg_stat_user_tables
                WHERE n_dead_tup > 1000
                  AND n_live_tup > 0
                  AND n_dead_tup::float / NULLIF(n_live_tup, 0) > ?
                ORDER BY n_dead_tup DESC
                LIMIT 10
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            String table = rs.getString("relname");
            long dead = rs.getLong("n_dead_tup");
            long live = rs.getLong("n_live_tup");
            double ratio = live == 0 ? 0 : (double) dead / live;

            return Finding.of(FindingSource.POSTGRES, Severity.MEDIUM,
                    "Table '%s' has %.0f%% dead tuples (%d dead / %d live)"
                            .formatted(table, ratio * 100, dead, live),
                    "Bloat inflates scan cost and suggests autovacuum is not keeping up.",
                    now(),
                    Finding.evidenceOf(
                            "table", table,
                            "n_dead_tup", String.valueOf(dead),
                            "n_live_tup", String.valueOf(live),
                            "last_autovacuum", String.valueOf(rs.getString("last_autovacuum"))));
        }, DEAD_TUPLE_RATIO);
    }

    private static Severity severityForLatency(double meanMs) {
        if (meanMs >= 1000) {
            return Severity.CRITICAL;
        }
        if (meanMs >= 500) {
            return Severity.HIGH;
        }
        return Severity.MEDIUM;
    }

    /** Collapses whitespace and truncates, so one pathological query cannot flood the context. */
    private static String normalise(String query) {
        if (query == null) {
            return "";
        }
        String collapsed = query.replaceAll("\\s+", " ").strip();
        return collapsed.length() > 1000 ? collapsed.substring(0, 1000) + " …" : collapsed;
    }

    private Instant now() {
        return clock.instant();
    }
}
