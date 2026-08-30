package ai.utkarsh.pop.infrastructure.investigator.postgres;

import ai.utkarsh.pop.infrastructure.config.InvestigationProperties;
import ai.utkarsh.pop.infrastructure.config.TargetDataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Query-level analysis against the observed database: execution plans, index inventory, and
 * table shape. These are the operations the agent reaches for once a slow statement has been
 * identified and it needs to explain <em>why</em> it is slow.
 *
 * <p>Every entry point routes through {@link SqlSafetyGuard} first. Nothing here executes a
 * caller-supplied statement without that check.
 */
@Slf4j
@Service
public class SqlAnalysisService {

    private final JdbcTemplate jdbc;
    private final SqlSafetyGuard guard;
    private final InvestigationProperties properties;

    SqlAnalysisService(@Qualifier(TargetDataSourceConfig.TARGET_JDBC_TEMPLATE) JdbcTemplate jdbc,
                       SqlSafetyGuard guard,
                       InvestigationProperties properties) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.properties = properties;
    }

    /**
     * Returns the execution plan for a statement.
     *
     * <p>Plain {@code EXPLAIN} only plans; it does not run the query. {@code EXPLAIN ANALYZE}
     * does run it, which is why it is gated on configuration rather than on the caller's word.
     *
     * @throws UnsafeSqlException if the statement is not provably read-only
     */
    public String explain(String sql) {
        String safe = guard.requireReadOnly(sql, properties.allowExplainAnalyze());

        String prefix = properties.allowExplainAnalyze()
                ? "EXPLAIN (ANALYZE, BUFFERS, COSTS, FORMAT TEXT) "
                : "EXPLAIN (COSTS, FORMAT TEXT) ";

        // If the caller already wrote EXPLAIN, don't stack another one on top.
        String statement = safe.toUpperCase(Locale.ROOT).startsWith("EXPLAIN") ? safe : prefix + safe;

        List<String> lines = jdbc.queryForList(statement, String.class);
        return String.join("\n", lines);
    }

    /** Indexes currently defined on a table, with their usage counts. */
    public List<IndexInfo> indexesOf(String table) {
        String sql = """
                SELECT i.indexrelname                AS index_name,
                       i.idx_scan                    AS scans,
                       pg_size_pretty(pg_relation_size(i.indexrelid)) AS size,
                       pg_get_indexdef(i.indexrelid) AS definition
                FROM pg_stat_user_indexes i
                WHERE i.relname = ?
                ORDER BY i.idx_scan DESC
                """;
        return jdbc.query(sql, (rs, rowNum) -> new IndexInfo(
                rs.getString("index_name"),
                rs.getLong("scans"),
                rs.getString("size"),
                rs.getString("definition")), requireIdentifier(table));
    }

    /** Column names, types and estimated distinct counts — the inputs to index selection. */
    public List<ColumnInfo> columnsOf(String table) {
        String sql = """
                SELECT a.attname                                        AS column_name,
                       format_type(a.atttypid, a.atttypmod)             AS data_type,
                       COALESCE(s.n_distinct, 0)                        AS n_distinct,
                       a.attnotnull                                     AS not_null
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                LEFT JOIN pg_stats s ON s.tablename = c.relname AND s.attname = a.attname
                WHERE c.relname = ?
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum
                """;
        return jdbc.query(sql, (rs, rowNum) -> new ColumnInfo(
                rs.getString("column_name"),
                rs.getString("data_type"),
                rs.getDouble("n_distinct"),
                rs.getBoolean("not_null")), requireIdentifier(table));
    }

    /** Row-count estimate and on-disk size. */
    public TableInfo tableInfo(String table) {
        String sql = """
                SELECT c.relname                                        AS table_name,
                       c.reltuples::bigint                              AS estimated_rows,
                       pg_size_pretty(pg_total_relation_size(c.oid))    AS total_size,
                       COALESCE(t.seq_scan, 0)                          AS seq_scan,
                       COALESCE(t.idx_scan, 0)                          AS idx_scan
                FROM pg_class c
                LEFT JOIN pg_stat_user_tables t ON t.relid = c.oid
                WHERE c.relname = ?
                  AND c.relkind = 'r'
                """;
        List<TableInfo> results = jdbc.query(sql, (rs, rowNum) -> new TableInfo(
                rs.getString("table_name"),
                rs.getLong("estimated_rows"),
                rs.getString("total_size"),
                rs.getLong("seq_scan"),
                rs.getLong("idx_scan")), requireIdentifier(table));

        if (results.isEmpty()) {
            throw new IllegalArgumentException("No such table: " + table);
        }
        return results.getFirst();
    }

    /** Tables in the public schema, so the agent can orient itself without guessing names. */
    public List<String> listTables() {
        return jdbc.queryForList("""
                SELECT relname
                FROM pg_stat_user_tables
                ORDER BY n_live_tup DESC
                LIMIT 200
                """, String.class);
    }

    /**
     * Suggests indexes for a table whose access pattern looks scan-heavy.
     *
     * <p>Deliberately a heuristic, and labelled as one: it proposes candidates from column
     * selectivity and existing-index gaps. The judgement about whether a suggestion is worth
     * acting on is left to the model and ultimately the operator — writing DDL is never
     * something this platform does.
     */
    public List<String> suggestIndexes(String table) {
        TableInfo info = tableInfo(table);
        List<IndexInfo> existing = indexesOf(table);
        List<ColumnInfo> columns = columnsOf(table);
        List<String> suggestions = new ArrayList<>();

        if (info.seqScan() <= info.idxScan()) {
            return List.of("Table '%s' is already served mostly by index scans (%d idx vs %d seq); "
                    .formatted(table, info.idxScan(), info.seqScan())
                    + "no index is obviously missing.");
        }

        for (ColumnInfo column : columns) {
            boolean alreadyIndexed = existing.stream()
                    .anyMatch(index -> index.definition().contains("(" + column.name() + ")")
                            || index.definition().contains("(" + column.name() + ",")
                            || index.definition().contains(" " + column.name() + ")"));
            if (alreadyIndexed) {
                continue;
            }
            // Foreign-key-shaped columns are the usual culprits behind a missing index.
            boolean looksLikeForeignKey = column.name().endsWith("_id");
            boolean selective = column.distinctEstimate() > 50 || column.distinctEstimate() < 0;
            if (looksLikeForeignKey && selective) {
                suggestions.add(("CREATE INDEX CONCURRENTLY idx_%s_%s ON %s (%s); "
                        + "-- candidate: unindexed foreign-key column on a table with %d sequential scans")
                        .formatted(table, column.name(), table, column.name(), info.seqScan()));
            }
        }

        if (suggestions.isEmpty()) {
            suggestions.add(("Table '%s' shows %d sequential scans over ~%d rows, but no single-column "
                    + "candidate stands out. Inspect the query predicates with explain_query.")
                    .formatted(table, info.seqScan(), info.estimatedRows()));
        }
        return suggestions;
    }

    /**
     * Table and index names cannot be bound as parameters, so they are validated as plain
     * identifiers before being used anywhere near a statement.
     *
     */
    private static String requireIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_$]{0,62}")) {
            throw new IllegalArgumentException("Not a valid table identifier: " + identifier);
        }
        return identifier;
    }

    public record IndexInfo(String name, long scans, String size, String definition) {
    }

    public record ColumnInfo(String name, String dataType, double distinctEstimate, boolean notNull) {
    }

    public record TableInfo(String name, long estimatedRows, String totalSize, long seqScan, long idxScan) {
    }
}
