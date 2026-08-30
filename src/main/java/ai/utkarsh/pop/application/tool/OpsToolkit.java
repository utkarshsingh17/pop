package ai.utkarsh.pop.application.tool;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import ai.utkarsh.pop.infrastructure.investigator.postgres.SqlAnalysisService;
import ai.utkarsh.pop.infrastructure.investigator.postgres.UnsafeSqlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The capabilities an investigation can draw on, independent of how they are exposed.
 *
 * <p>This class holds the actual work. Two thin adapters publish it: {@code OpsTools} for the
 * in-process Spring AI agent and {@code OpsMcpTools} for external MCP clients. Keeping the
 * logic here means the two surfaces cannot drift apart, and it keeps Spring AI and MCP
 * annotations off the code that does the real work.
 *
 * <p>Every method returns a human-readable string. That is deliberate — the consumer is a
 * language model, and prose with embedded numbers is easier for it to reason over and quote
 * than a JSON blob it has to re-serialise into its answer.
 */
@Slf4j
@Service
public class OpsToolkit {

    private final Map<FindingSource, InvestigatorPort> investigators;
    private final SqlAnalysisService sqlAnalysis;
    private final InvestigationContext context;

    OpsToolkit(List<InvestigatorPort> investigators,
               SqlAnalysisService sqlAnalysis,
               InvestigationContext context) {
        this.investigators = investigators.stream()
                .collect(Collectors.toMap(InvestigatorPort::source, port -> port));
        this.sqlAnalysis = sqlAnalysis;
        this.context = context;
    }

    /**
     * Runs every check for one evidence source and attaches what it found to the active
     * investigation, so the findings survive into the stored record and the final answer.
     */
    public String sweep(FindingSource source) {
        Investigation investigation = context.require();
        InvestigatorPort investigator = investigators.get(source);
        if (investigator == null) {
            return "No investigator is configured for " + source + ".";
        }

        List<Finding> findings = investigator.investigate(investigation.service(), investigation.timeRange());
        findings.forEach(investigation::recordFinding);

        if (findings.isEmpty()) {
            return "No anomalies found in %s for service '%s' over the window %s to %s."
                    .formatted(source, investigation.service(), investigation.timeRange().from(),
                            investigation.timeRange().to());
        }

        return "Found %d item(s) in %s:\n".formatted(findings.size(), source)
                + findings.stream().map(OpsToolkit::render).collect(Collectors.joining("\n"));
    }

    public String listTables() {
        List<String> tables = sqlAnalysis.listTables();
        if (tables.isEmpty()) {
            return "The target database has no user tables (or statistics are unavailable).";
        }
        return "Tables, largest first: " + String.join(", ", tables);
    }

    public String describeTable(String table) {
        try {
            SqlAnalysisService.TableInfo info = sqlAnalysis.tableInfo(table);
            List<SqlAnalysisService.ColumnInfo> columns = sqlAnalysis.columnsOf(table);
            List<SqlAnalysisService.IndexInfo> indexes = sqlAnalysis.indexesOf(table);

            StringBuilder out = new StringBuilder();
            out.append("Table '%s': ~%d rows, %s on disk, %d sequential scans vs %d index scans.\n"
                    .formatted(info.name(), info.estimatedRows(), info.totalSize(),
                            info.seqScan(), info.idxScan()));

            out.append("Columns:\n");
            columns.forEach(c -> out.append("  - %s %s%s\n"
                    .formatted(c.name(), c.dataType(), c.notNull() ? " NOT NULL" : "")));

            if (indexes.isEmpty()) {
                out.append("Indexes: none.\n");
            } else {
                out.append("Indexes:\n");
                indexes.forEach(i -> out.append("  - %s (%d scans, %s): %s\n"
                        .formatted(i.name(), i.scans(), i.size(), i.definition())));
            }
            return out.toString();
        } catch (IllegalArgumentException e) {
            return "Cannot describe table: " + e.getMessage();
        }
    }

    /**
     * Returns the planner's execution plan for a statement.
     *
     * <p>Refusals are returned as text rather than thrown, so the model learns it may not run
     * that statement and can choose a different approach, instead of the loop dying on an
     * exception it cannot see.
     */
    public String explainQuery(String sql) {
        try {
            return "Execution plan:\n" + sqlAnalysis.explain(sql);
        } catch (UnsafeSqlException e) {
            log.warn("Refused to explain unsafe SQL: {}", e.getMessage());
            return "REFUSED: " + e.getMessage()
                    + " Only read-only SELECT/WITH/EXPLAIN statements can be analysed.";
        } catch (RuntimeException e) {
            return "The database rejected that statement: " + e.getMessage();
        }
    }

    public String suggestIndexes(String table) {
        try {
            return String.join("\n", sqlAnalysis.suggestIndexes(table));
        } catch (IllegalArgumentException e) {
            return "Cannot analyse table: " + e.getMessage();
        }
    }

    /** What the investigation knows so far — lets the model take stock before concluding. */
    public String evidenceSoFar() {
        Investigation investigation = context.require();
        if (investigation.findings().isEmpty()) {
            return "No findings recorded yet. Run the database and metrics sweeps first.";
        }
        return "Evidence gathered so far (highest severity: %s):\n%s"
                .formatted(investigation.highestSeverity(),
                        investigation.findings().stream()
                                .map(OpsToolkit::render)
                                .collect(Collectors.joining("\n")));
    }

    private static String render(Finding finding) {
        String evidence = finding.evidence().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        return "  [%s] %s — %s%s".formatted(
                finding.severity(), finding.title(), finding.detail(),
                evidence.isEmpty() ? "" : " (" + evidence + ")");
    }
}
