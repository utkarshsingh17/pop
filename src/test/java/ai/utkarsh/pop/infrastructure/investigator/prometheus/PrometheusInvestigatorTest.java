package ai.utkarsh.pop.infrastructure.investigator.prometheus;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

/**
 * Covers the part worth covering: translating an observed metric value into a severity.
 * The HTTP call itself is Spring's declarative client and is not re-tested here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrometheusInvestigatorTest {

    private static final ServiceName SERVICE = ServiceName.of("order-service");
    private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");

    @Mock
    private PrometheusQueryGateway gateway;

    private PrometheusInvestigator investigator;

    @BeforeEach
    void setUp() {
        investigator = new PrometheusInvestigator(gateway, Clock.fixed(NOW, ZoneOffset.UTC));
        // Default: every metric healthy / absent. Individual tests override one check.
        when(gateway.scalar(anyString())).thenReturn(Optional.empty());
    }

    private List<Finding> investigate() {
        return investigator.investigate(SERVICE, TimeRange.lastly(Duration.ofHours(1), NOW));
    }

    @Test
    void investigate_whenNoMetricsExist_shouldProduceNoFindings() {
        assertThat(investigate()).isEmpty();
    }

    @Test
    void investigate_whenServiceIsDown_shouldReportCritical() {
        when(gateway.scalar(contains("up{"))).thenReturn(Optional.of(0.0));

        assertThat(investigate())
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.title()).contains("DOWN");
                    assertThat(f.source()).isEqualTo(FindingSource.PROMETHEUS);
                });
    }

    @Test
    void investigate_whenServiceIsUp_shouldNotReportAvailability() {
        when(gateway.scalar(contains("up{"))).thenReturn(Optional.of(1.0));

        assertThat(investigate()).isEmpty();
    }

    @Test
    void investigate_shouldGradeLatencyBySeverity() {
        assertLatency(0.30, null);
        assertLatency(0.60, Severity.MEDIUM);
        assertLatency(1.20, Severity.HIGH);
        assertLatency(2.50, Severity.CRITICAL);
    }

    private void assertLatency(double seconds, Severity expected) {
        when(gateway.scalar(contains("histogram_quantile"))).thenReturn(Optional.of(seconds));

        List<Finding> latency = investigate().stream()
                .filter(f -> f.title().contains("p99"))
                .toList();

        if (expected == null) {
            assertThat(latency).as("latency %.2fs should be healthy", seconds).isEmpty();
        } else {
            assertThat(latency).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(expected));
        }
    }

    @Test
    void investigate_shouldGradeErrorRateBySeverity() {
        when(gateway.scalar(contains("outcome=\"SERVER_ERROR\""))).thenReturn(Optional.of(0.07));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("5xx"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.HIGH);
                    assertThat(f.title()).contains("7.0%");
                });
    }

    @Test
    void investigate_shouldReportHeapPressure() {
        when(gateway.scalar(contains("jvm_memory_used_bytes"))).thenReturn(Optional.of(0.97));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("heap"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.title()).contains("97%");
                });
    }

    @Test
    void investigate_shouldReportConnectionPoolSaturation() {
        when(gateway.scalar(contains("hikaricp_connections_active"))).thenReturn(Optional.of(0.85));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("connection pool"))
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MEDIUM));
    }

    @Test
    void investigate_shouldAttachQueryAndValueAsEvidence() {
        when(gateway.scalar(contains("up{"))).thenReturn(Optional.of(0.0));

        Finding finding = investigate().getFirst();

        assertThat(finding.evidence())
                .containsEntry("value", "0.0")
                .containsKey("query")
                .containsEntry("check", "Service availability");
        assertThat(finding.observedAt()).isEqualTo(NOW);
    }

    @Test
    void investigate_whenPrometheusUnreachable_shouldDegradeToInfoFindings() {
        when(gateway.scalar(anyString())).thenThrow(new IllegalStateException("connection refused"));

        List<Finding> findings = investigate();

        assertThat(findings)
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.title()).startsWith("Check unavailable:");
                    assertThat(f.detail()).contains("connection refused");
                });
    }

    @Test
    void investigate_shouldScopeEveryQueryToTheRequestedService() {
        when(gateway.scalar(contains("up{"))).thenReturn(Optional.of(0.0));

        assertThat(investigate().getFirst().evidence().get("query"))
                .contains("service=\"order-service\"");
    }
}
