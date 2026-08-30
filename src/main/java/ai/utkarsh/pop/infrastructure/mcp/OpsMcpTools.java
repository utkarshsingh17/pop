package ai.utkarsh.pop.infrastructure.mcp;

import ai.utkarsh.pop.application.tool.InvestigationContext;
import ai.utkarsh.pop.application.tool.OpsToolkit;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.TimeRange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * MCP tool surface — lets an external agent (Claude Code, Claude Desktop) drive this platform
 * directly, rather than only through pop's own agent loop.
 *
 * <p>Separate class from {@code OpsTools} on purpose. Both delegate to {@link OpsToolkit}, but
 * the Spring AI {@code @Tool} mechanism and the MCP {@code @McpTool} mechanism must not be
 * pointed at the same methods — registering one method through both produces duplicate tool
 * definitions.
 *
 * <p>The sweeps need an {@link Investigation} bound to the thread to record findings onto. An
 * MCP caller has no investigation, so each sweep creates an ephemeral one for the duration of
 * the call: the findings come back in the response, and nothing is persisted. MCP callers get
 * the platform's eyes, not its memory.
 */
@Component
public class OpsMcpTools {

    private final OpsToolkit toolkit;
    private final InvestigationContext context;
    private final Clock clock;

    OpsMcpTools(OpsToolkit toolkit, InvestigationContext context, Clock clock) {
        this.toolkit = toolkit;
        this.context = context;
        this.clock = clock;
    }

    @McpTool(name = "sweep_database",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Run all database health checks for a service and report anomalies: "
                    + "slow queries, sequential scans on large tables, lock waits, connection "
                    + "saturation, idle-in-transaction sessions and table bloat.")
    public String sweepDatabase(
            @McpToolParam(description = "Service to investigate, e.g. 'order-service'", required = true)
            String service,
            @McpToolParam(description = "Lookback window in minutes (default 60)", required = false)
            Integer lookbackMinutes) {
        return withEphemeralInvestigation(service, lookbackMinutes,
                () -> toolkit.sweep(FindingSource.POSTGRES));
    }

    @McpTool(name = "sweep_metrics",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Run all Prometheus metric checks for a service and report threshold "
                    + "breaches: availability, p99 latency, 5xx error rate, JVM heap, GC time, "
                    + "CPU and connection pool utilisation.")
    public String sweepMetrics(
            @McpToolParam(description = "Service to investigate, e.g. 'order-service'", required = true)
            String service,
            @McpToolParam(description = "Lookback window in minutes (default 60)", required = false)
            Integer lookbackMinutes) {
        return withEphemeralInvestigation(service, lookbackMinutes,
                () -> toolkit.sweep(FindingSource.PROMETHEUS));
    }

    @McpTool(name = "sweep_runtime",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Read a registered service's Spring Boot Actuator directly and report "
                    + "what is wrong right now: health and failing components, JVM heap and "
                    + "metaspace, blocked threads, GC pause time, CPU, request latency, connection "
                    + "pool and file descriptors. Each result carries a suggested next check.")
    public String sweepRuntime(
            @McpToolParam(description = "Service to investigate, e.g. 'order-service'", required = true)
            String service,
            @McpToolParam(description = "Lookback window in minutes (default 60)", required = false)
            Integer lookbackMinutes) {
        return withEphemeralInvestigation(service, lookbackMinutes,
                () -> toolkit.sweep(FindingSource.ACTUATOR));
    }

    @McpTool(name = "sweep_logs",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Read the tail of a registered service's log and report fatal errors, "
                    + "the ERROR rate, and repeating exception types. The log outlives the "
                    + "process, so this is the only source that can explain a crash after the "
                    + "service has stopped answering.")
    public String sweepLogs(
            @McpToolParam(description = "Service to investigate, e.g. 'order-service'", required = true)
            String service,
            @McpToolParam(description = "Lookback window in minutes (default 60)", required = false)
            Integer lookbackMinutes) {
        return withEphemeralInvestigation(service, lookbackMinutes,
                () -> toolkit.sweep(FindingSource.LOGS));
    }

    @McpTool(name = "list_tables",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "List tables in the database under investigation, largest first.")
    public String listTables() {
        return toolkit.listTables();
    }

    @McpTool(name = "describe_table",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Show a table's row count, size, columns, existing indexes, and its "
                    + "sequential-versus-index scan counts.")
    public String describeTable(
            @McpToolParam(description = "Exact table name, e.g. 'orders'", required = true) String table) {
        return toolkit.describeTable(table);
    }

    @McpTool(name = "explain_query",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Return the Postgres execution plan for a read-only SELECT statement. "
                    + "Write statements are refused.")
    public String explainQuery(
            @McpToolParam(description = "A single read-only SQL SELECT statement", required = true)
            String sql) {
        return toolkit.explainQuery(sql);
    }

    @McpTool(name = "suggest_indexes",
            annotations = @McpTool.McpAnnotations(
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            description = "Propose candidate indexes for a scan-heavy table. Heuristic suggestions "
                    + "to evaluate — this tool never creates indexes.")
    public String suggestIndexes(
            @McpToolParam(description = "Exact table name, e.g. 'orders'", required = true) String table) {
        return toolkit.suggestIndexes(table);
    }

    private String withEphemeralInvestigation(String service, Integer lookbackMinutes,
                                              java.util.function.Supplier<String> action) {
        Duration lookback = Duration.ofMinutes(
                lookbackMinutes == null || lookbackMinutes <= 0 ? 60 : lookbackMinutes);

        Investigation ephemeral = Investigation.open(
                "MCP sweep", ServiceName.of(service),
                TimeRange.lastly(lookback, clock.instant()), clock.instant());
        ephemeral.begin(clock.instant());

        return context.runWithin(ephemeral, action);
    }
}
