package ai.utkarsh.pop.application.tool;

import ai.utkarsh.pop.domain.model.FindingSource;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI tool surface — what the in-process agent can call.
 *
 * <p>Descriptions are written for the model, not for a developer: they say when to reach for
 * the tool, not merely what it does, because that is what the model uses to choose. All real
 * work is delegated to {@link OpsToolkit}.
 *
 * <p>Registered via {@code .defaultTools(...)} on the ChatClient. The MCP surface is a separate
 * class using {@code @McpTool} — the two mechanisms must not both be pointed at the same
 * methods.
 */
@Component
public class OpsTools {

    private final OpsToolkit toolkit;

    OpsTools(OpsToolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Tool(name = "sweep_database", description = """
            Run every database health check against the service's database and return what looks
            abnormal: slow queries, sequential scans on large tables, lock waits, connection
            saturation, idle-in-transaction sessions and table bloat.
            Call this first for any question about slowness, timeouts or database load.
            """)
    public String sweepDatabase() {
        return toolkit.sweep(FindingSource.POSTGRES);
    }

    @Tool(name = "sweep_metrics", description = """
            Run every metrics check for the service and return what breached a threshold:
            availability, p99 latency, 5xx error rate, JVM heap, GC time, CPU and connection
            pool utilisation.
            Call this to establish whether the symptom is visible from the outside and when it
            started.
            """)
    public String sweepMetrics() {
        return toolkit.sweep(FindingSource.PROMETHEUS);
    }

    @Tool(name = "sweep_runtime", description = """
            Read the service's own Spring Boot Actuator directly and return what looks wrong right
            now: health and failing components, JVM heap and metaspace, blocked and live threads,
            GC pause time, CPU, mean request latency, connection pool and file descriptors.
            Each result carries a concrete next check.
            Use this for 'is it healthy right now' and for internal JVM state. It reads the live
            process, so it has no history — use sweep_metrics to find out when something changed.
            Only works for services registered with a URL.
            """)
    public String sweepRuntime() {
        return toolkit.sweep(FindingSource.ACTUATOR);
    }

    @Tool(name = "list_tables", description = """
            List the tables in the database under investigation, largest first.
            Use this to orient yourself before describing or analysing a specific table.
            """)
    public String listTables() {
        return toolkit.listTables();
    }

    @Tool(name = "describe_table", description = """
            Show a table's row count, size, columns and existing indexes, plus how often it is
            read by sequential versus index scan.
            Use this to check whether a slow query's predicate has a supporting index.
            """)
    public String describeTable(
            @ToolParam(description = "Exact table name, e.g. 'orders'") String table) {
        return toolkit.describeTable(table);
    }

    @Tool(name = "explain_query", description = """
            Return the Postgres execution plan for a read-only SELECT statement, showing which
            scans and joins the planner chose and their estimated cost.
            Use this to confirm *why* a specific query is slow. Only SELECT/WITH/EXPLAIN
            statements are permitted; anything that writes will be refused.
            """)
    public String explainQuery(
            @ToolParam(description = "A single read-only SQL SELECT statement") String sql) {
        return toolkit.explainQuery(sql);
    }

    @Tool(name = "suggest_indexes", description = """
            Propose candidate indexes for a table whose access pattern is scan-heavy, based on
            its columns, existing indexes and scan counts.
            These are heuristics to evaluate, not commands to run — pop never creates indexes.
            """)
    public String suggestIndexes(
            @ToolParam(description = "Exact table name, e.g. 'orders'") String table) {
        return toolkit.suggestIndexes(table);
    }

    @Tool(name = "evidence_so_far", description = """
            Re-read every finding recorded during this investigation, with severities.
            Use this to take stock before writing your conclusion.
            """)
    public String evidenceSoFar() {
        return toolkit.evidenceSoFar();
    }
}
