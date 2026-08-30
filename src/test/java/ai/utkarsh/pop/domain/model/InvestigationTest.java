package ai.utkarsh.pop.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");
    private static final ServiceName SERVICE = ServiceName.of("order-service");

    private static Investigation pending() {
        return Investigation.open("why is it slow?", SERVICE, TimeRange.lastly(Duration.ofHours(1), NOW), NOW);
    }

    private static Investigation investigating() {
        Investigation investigation = pending();
        investigation.begin(NOW);
        return investigation;
    }

    private static Finding finding(Severity severity) {
        return Finding.of(FindingSource.POSTGRES, severity, "Slow query", "seq scan on orders",
                NOW, Map.of("calls", "1200"));
    }

    @Test
    void open_shouldStartPendingWithNoFindings() {
        Investigation investigation = pending();

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.PENDING);
        assertThat(investigation.findings()).isEmpty();
        assertThat(investigation.diagnosis()).isEmpty();
        assertThat(investigation.startedAt()).isEmpty();
        assertThat(investigation.id()).isNotNull();
    }

    @Test
    void open_whenQuestionBlank_shouldThrow() {
        assertThatThrownBy(() -> Investigation.open("  ", SERVICE, TimeRange.lastly(Duration.ofHours(1), NOW), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void begin_shouldMoveToInvestigating() {
        Investigation investigation = pending();

        investigation.begin(NOW);

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.INVESTIGATING);
        assertThat(investigation.startedAt()).contains(NOW);
    }

    @Test
    void begin_whenAlreadyInvestigating_shouldThrow() {
        Investigation investigation = investigating();

        assertThatThrownBy(() -> investigation.begin(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected PENDING but was INVESTIGATING");
    }

    @Test
    void recordFinding_whenPending_shouldThrow() {
        Investigation investigation = pending();

        assertThatThrownBy(() -> investigation.recordFinding(finding(Severity.HIGH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("record a finding");
    }

    @Test
    void recordFinding_afterConclusion_shouldThrow() {
        Investigation investigation = investigating();
        investigation.concludeWith(new Diagnosis("missing index", Confidence.HIGH, "", List.of(), List.of()), NOW);

        assertThatThrownBy(() -> investigation.recordFinding(finding(Severity.HIGH)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concludeWith_shouldCompleteAndRetainDiagnosis() {
        Investigation investigation = investigating();
        investigation.recordFinding(finding(Severity.HIGH));
        Diagnosis diagnosis = new Diagnosis("missing index on orders.customer_id", Confidence.HIGH,
                "Sequential scan dominates latency.", List.of("CREATE INDEX ..."), List.of("Slow query"));

        investigation.concludeWith(diagnosis, NOW.plusSeconds(30));

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(investigation.diagnosis()).contains(diagnosis);
        assertThat(investigation.completedAt()).contains(NOW.plusSeconds(30));
    }

    @Test
    void concludeWith_whenPending_shouldThrow() {
        Investigation investigation = pending();

        assertThatThrownBy(() -> investigation.concludeWith(Diagnosis.inconclusive("no data"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conclude");
    }

    @Test
    void fail_shouldMoveToFailedAndKeepReason() {
        Investigation investigation = investigating();

        investigation.fail("prometheus unreachable", NOW);

        assertThat(investigation.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(investigation.failureReason()).contains("prometheus unreachable");
    }

    @Test
    void fail_whenAlreadyCompleted_shouldThrow() {
        Investigation investigation = investigating();
        investigation.concludeWith(Diagnosis.inconclusive("nothing found"), NOW);

        assertThatThrownBy(() -> investigation.fail("too late", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMPLETED");
    }

    @Test
    void fail_withBlankReason_shouldRecordPlaceholder() {
        Investigation investigation = investigating();

        investigation.fail("  ", NOW);

        assertThat(investigation.failureReason()).contains("unknown failure");
    }

    @Test
    void highestSeverity_shouldReturnMaximumAcrossFindings() {
        Investigation investigation = investigating();
        investigation.recordFinding(finding(Severity.LOW));
        investigation.recordFinding(finding(Severity.CRITICAL));
        investigation.recordFinding(finding(Severity.MEDIUM));

        assertThat(investigation.highestSeverity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void highestSeverity_whenNoFindings_shouldBeInfo() {
        assertThat(investigating().highestSeverity()).isEqualTo(Severity.INFO);
    }

    @Test
    void findingsFrom_shouldFilterBySource() {
        Investigation investigation = investigating();
        investigation.recordFinding(finding(Severity.HIGH));
        investigation.recordFinding(Finding.of(FindingSource.PROMETHEUS, Severity.MEDIUM, "p99 latency",
                "above threshold", NOW, Map.of()));

        assertThat(investigation.findingsFrom(FindingSource.POSTGRES)).hasSize(1);
        assertThat(investigation.findingsFrom(FindingSource.PROMETHEUS)).hasSize(1);
    }

    @Test
    void findings_shouldBeUnmodifiable() {
        Investigation investigation = investigating();

        assertThatThrownBy(() -> investigation.findings().add(finding(Severity.LOW)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
