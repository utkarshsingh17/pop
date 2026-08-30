package ai.utkarsh.pop.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"order-service", "orders_v2", "svc.prod", "job:rate:5m"})
    void serviceName_shouldAcceptPrometheusSafeLabels(String value) {
        assertThat(ServiceName.of(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order\"} or up{", "svc name", "svc,other", "svc}", "a|b"})
    void serviceName_shouldRejectPromqlInjectionAttempts(String value) {
        assertThatThrownBy(() -> ServiceName.of(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceName_shouldRejectBlank() {
        assertThatThrownBy(() -> ServiceName.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeRange_shouldRejectInvertedWindow() {
        assertThatThrownBy(() -> new TimeRange(NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
    }

    @Test
    void timeRange_shouldRejectEmptyWindow() {
        assertThatThrownBy(() -> new TimeRange(NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeRange_shouldRejectWindowBeyondSevenDays() {
        assertThatThrownBy(() -> TimeRange.lastly(Duration.ofDays(8), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void timeRange_lastly_shouldEndAtNow() {
        TimeRange range = TimeRange.lastly(Duration.ofHours(2), NOW);

        assertThat(range.to()).isEqualTo(NOW);
        assertThat(range.from()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(range.duration()).isEqualTo(Duration.ofHours(2));
        assertThat(range.contains(NOW.minusSeconds(60))).isTrue();
        assertThat(range.contains(NOW)).isFalse();
    }

    @Test
    void finding_shouldDefensivelyCopyEvidence() {
        Map<String, String> mutable = new java.util.HashMap<>(Map.of("calls", "10"));
        Finding finding = Finding.of(FindingSource.POSTGRES, Severity.LOW, "t", "d", NOW, mutable);

        mutable.put("calls", "999");

        assertThat(finding.evidence()).containsEntry("calls", "10");
    }

    @Test
    void finding_shouldRejectBlankTitle() {
        assertThatThrownBy(() -> Finding.of(FindingSource.POSTGRES, Severity.LOW, " ", "d", NOW, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finding_evidenceOf_shouldRejectOddArgumentCount() {
        assertThatThrownBy(() -> Finding.evidenceOf("a", "b", "c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosis_shouldRejectBlankRootCause() {
        assertThatThrownBy(() -> new Diagnosis("", Confidence.HIGH, "s", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void diagnosis_shouldDefaultNullCollectionsToEmpty() {
        Diagnosis diagnosis = new Diagnosis("cause", Confidence.LOW, null, null, null);

        assertThat(diagnosis.remediationSteps()).isEmpty();
        assertThat(diagnosis.supportingFindings()).isEmpty();
        assertThat(diagnosis.summary()).isEmpty();
    }

    @Test
    void severity_isAtLeast_shouldCompareOrdinally() {
        assertThat(Severity.CRITICAL.isAtLeast(Severity.HIGH)).isTrue();
        assertThat(Severity.LOW.isAtLeast(Severity.HIGH)).isFalse();
        assertThat(Severity.HIGH.isAtLeast(Severity.HIGH)).isTrue();
    }
}
