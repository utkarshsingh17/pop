package ai.utkarsh.pop.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoredServiceTest {

    private static final ServiceName NAME = ServiceName.of("order-service");
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
    private static final DatabaseTarget TARGET =
            new DatabaseTarget("jdbc:postgresql://db:5432/shop", "pop_readonly", "secret");

    @Test
    void shouldFallBackToTheServiceNameAsThePrometheusLabel() {
        MonitoredService service = MonitoredService.register(NAME, null, null, null, null, NOW);

        assertThat(service.effectivePrometheusLabel()).isEqualTo("order-service");
        assertThat(service.prometheusLabel()).isEmpty();
    }

    @Test
    void shouldRegisterWithoutADatabase() {
        MonitoredService service = MonitoredService.register(NAME, null, null, null, null, NOW);

        assertThat(service.hasDatabase()).isFalse();
        assertThat(service.enabled()).isTrue();
    }

    @Test
    void shouldRejectAPrometheusLabelThatCouldInjectPromql() {
        assertThatThrownBy(() -> MonitoredService.register(NAME, "svc\"} or up{", null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prometheus label");
    }

    @Test
    void shouldTrackUpdateTime() {
        MonitoredService service = MonitoredService.register(NAME, null, null, null, null, NOW);
        Instant later = NOW.plusSeconds(60);

        service.updateDatabase(TARGET, later);

        assertThat(service.updatedAt()).isEqualTo(later);
        assertThat(service.registeredAt()).isEqualTo(NOW);
        assertThat(service.database()).contains(TARGET);
    }

    @Test
    void disableShouldStopItBeingInvestigated() {
        MonitoredService service = MonitoredService.register(NAME, null, TARGET, null, null, NOW);

        service.disable(NOW.plusSeconds(1));

        assertThat(service.enabled()).isFalse();
    }

    @Test
    void databaseTargetShouldRejectANonPostgresUrl() {
        assertThatThrownBy(() -> new DatabaseTarget("jdbc:mysql://db:3306/shop", "u", "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jdbc:postgresql://");
    }

    @Test
    void databaseTargetShouldNotPrintItsPassword() {
        // This lands in log lines and exception messages; the generated record toString would leak it.
        assertThat(TARGET.toString()).doesNotContain("secret").contains("***");
    }

    @Test
    void redactedShouldDropThePassword() {
        assertThat(TARGET.redacted().password()).isEmpty();
        assertThat(TARGET.redacted().jdbcUrl()).isEqualTo(TARGET.jdbcUrl());
    }
}
