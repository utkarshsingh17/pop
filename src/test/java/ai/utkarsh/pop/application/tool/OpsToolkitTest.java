package ai.utkarsh.pop.application.tool;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import ai.utkarsh.pop.infrastructure.investigator.postgres.SqlAnalysisService;
import ai.utkarsh.pop.infrastructure.investigator.postgres.UnsafeSqlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsToolkitTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Mock
    private InvestigatorPort postgresInvestigator;

    @Mock
    private SqlAnalysisService sqlAnalysis;

    private InvestigationContext context;
    private OpsToolkit toolkit;
    private Investigation investigation;

    @BeforeEach
    void setUp() {
        when(postgresInvestigator.source()).thenReturn(FindingSource.POSTGRES);
        context = new InvestigationContext();
        toolkit = new OpsToolkit(List.of(postgresInvestigator), sqlAnalysis, context);

        investigation = Investigation.open("why slow?", ServiceName.of("order-service"),
                TimeRange.lastly(Duration.ofHours(1), NOW), NOW);
        investigation.begin(NOW);
    }

    private static Finding finding() {
        return Finding.of(FindingSource.POSTGRES, Severity.HIGH, "Slow query",
                "seq scan on orders", NOW, Map.of("calls", "1200"));
    }

    @Test
    void sweep_shouldRecordFindingsOntoTheBoundInvestigation() {
        when(postgresInvestigator.investigate(any(), any())).thenReturn(List.of(finding()));

        String output = context.runWithin(investigation, () -> toolkit.sweep(FindingSource.POSTGRES));

        assertThat(investigation.findings()).hasSize(1);
        assertThat(output).contains("Found 1 item(s)").contains("Slow query").contains("calls=1200");
    }

    @Test
    void sweep_whenNothingFound_shouldSaySoWithoutRecording() {
        when(postgresInvestigator.investigate(any(), any())).thenReturn(List.of());

        String output = context.runWithin(investigation, () -> toolkit.sweep(FindingSource.POSTGRES));

        assertThat(investigation.findings()).isEmpty();
        assertThat(output).contains("No anomalies found").contains("order-service");
    }

    @Test
    void sweep_whenNoInvestigatorConfigured_shouldReportRatherThanThrow() {
        String output = context.runWithin(investigation, () -> toolkit.sweep(FindingSource.PROMETHEUS));

        assertThat(output).contains("No investigator is configured");
    }

    @Test
    void sweep_outsideAnInvestigation_shouldThrow() {
        assertThatThrownBy(() -> toolkit.sweep(FindingSource.POSTGRES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No investigation is bound");
    }

    @Test
    void explainQuery_whenSqlIsRefused_shouldReturnRefusalTextNotThrow() {
        when(sqlAnalysis.explain(any())).thenThrow(new UnsafeSqlException("contains DROP"));

        String output = toolkit.explainQuery("DROP TABLE orders");

        // The model must see the refusal so it can change approach; an exception would
        // terminate the agent loop instead.
        assertThat(output).startsWith("REFUSED:").contains("contains DROP");
    }

    @Test
    void explainQuery_whenDatabaseRejects_shouldReturnMessage() {
        when(sqlAnalysis.explain(any())).thenThrow(new IllegalStateException("syntax error"));

        assertThat(toolkit.explainQuery("SELECT bad syntax"))
                .contains("The database rejected that statement")
                .contains("syntax error");
    }

    @Test
    void explainQuery_onSuccess_shouldReturnThePlan() {
        when(sqlAnalysis.explain(any())).thenReturn("Seq Scan on orders  (cost=0.00..1.00)");

        assertThat(toolkit.explainQuery("SELECT * FROM orders"))
                .contains("Execution plan:")
                .contains("Seq Scan on orders");
    }

    @Test
    void describeTable_shouldRenderColumnsAndIndexes() {
        when(sqlAnalysis.tableInfo("orders"))
                .thenReturn(new SqlAnalysisService.TableInfo("orders", 400_000, "52 MB", 900, 12));
        when(sqlAnalysis.columnsOf("orders")).thenReturn(List.of(
                new SqlAnalysisService.ColumnInfo("id", "bigint", -1, true),
                new SqlAnalysisService.ColumnInfo("customer_id", "bigint", 5000, true)));
        when(sqlAnalysis.indexesOf("orders")).thenReturn(List.of(
                new SqlAnalysisService.IndexInfo("orders_pkey", 12, "8 MB", "CREATE UNIQUE INDEX ... (id)")));

        String output = toolkit.describeTable("orders");

        assertThat(output)
                .contains("~400000 rows")
                .contains("900 sequential scans vs 12 index scans")
                .contains("customer_id bigint NOT NULL")
                .contains("orders_pkey");
    }

    @Test
    void describeTable_whenTableMissing_shouldReturnMessage() {
        when(sqlAnalysis.tableInfo("nope")).thenThrow(new IllegalArgumentException("No such table: nope"));

        assertThat(toolkit.describeTable("nope")).contains("Cannot describe table").contains("nope");
    }

    @Test
    void evidenceSoFar_shouldSummariseRecordedFindings() {
        investigation.recordFinding(finding());

        String output = context.runWithin(investigation, () -> toolkit.evidenceSoFar());

        assertThat(output).contains("highest severity: HIGH").contains("Slow query");
    }

    @Test
    void evidenceSoFar_whenNothingRecorded_shouldPromptTheModelToSweep() {
        String output = context.runWithin(investigation, () -> toolkit.evidenceSoFar());

        assertThat(output).contains("No findings recorded yet");
    }

    @Test
    void listTables_whenEmpty_shouldExplainRatherThanReturnBlank() {
        when(sqlAnalysis.listTables()).thenReturn(List.of());

        assertThat(toolkit.listTables()).contains("no user tables");
    }
}
