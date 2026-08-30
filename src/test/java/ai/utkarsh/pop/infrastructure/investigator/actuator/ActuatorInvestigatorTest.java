package ai.utkarsh.pop.infrastructure.investigator.actuator;

import ai.utkarsh.pop.domain.model.ActuatorEndpoint;
import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
import ai.utkarsh.pop.infrastructure.investigator.actuator.ActuatorClient.ActuatorMetric;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActuatorInvestigatorTest {

    private static final ServiceName SERVICE = ServiceName.of("order-service");
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final ActuatorEndpoint ENDPOINT = new ActuatorEndpoint("http://localhost:3001");

    @Mock
    private MonitoredServiceRepository services;

    @Mock
    private ActuatorClient client;

    private ActuatorInvestigator investigator;

    @BeforeEach
    void setUp() {
        investigator = new ActuatorInvestigator(services, client,
                Clock.fixed(NOW, ZoneOffset.UTC));
        registerWithActuator();
        when(client.health(any())).thenReturn(Optional.of(Map.of("status", "UP")));
        when(client.metric(any(), any(), any())).thenReturn(Optional.empty());
        when(client.metric(any(), any())).thenReturn(Optional.empty());
    }

    private void registerWithActuator() {
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.of(
                MonitoredService.register(SERVICE, null, null, ENDPOINT, null, NOW)));
    }

    private static ActuatorMetric gauge(double value) {
        return new ActuatorMetric("m", Map.of("VALUE", value));
    }

    private List<Finding> investigate() {
        return investigator.investigate(SERVICE, TimeRange.lastly(Duration.ofHours(1), NOW));
    }

    @Test
    void shouldReportTheActuatorSource() {
        assertThat(investigator.source()).isEqualTo(FindingSource.ACTUATOR);
    }

    @Test
    void shouldProduceNothingForAServiceWithNoActuatorRegistered() {
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.of(
                MonitoredService.register(SERVICE, null, null, null, null, NOW)));

        // No endpoint is not an error — there is simply nothing to ask.
        assertThat(investigate()).isEmpty();
    }

    @Test
    void shouldProduceNothingForAnUnregisteredService() {
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.empty());

        assertThat(investigate()).isEmpty();
    }

    @Test
    void shouldStaySilentWhenEverythingIsHealthy() {
        assertThat(investigate()).isEmpty();
    }

    @Test
    void shouldFlagAHeapNearItsLimitAndSuggestAHeapDump() {
        when(client.metric(any(), eq("jvm.memory.used"), eq("area:heap")))
                .thenReturn(Optional.of(gauge(950_000_000)));
        when(client.metric(any(), eq("jvm.memory.max"), eq("area:heap")))
                .thenReturn(Optional.of(gauge(1_000_000_000)));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("heap"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.title()).contains("95%");
                    // The suggestion is the point — a bare number leaves the model to invent one.
                    assertThat(f.detail()).contains("heapdump");
                });
    }

    @Test
    void shouldReportHealthDownWithTheFailingComponent() {
        when(client.health(any())).thenReturn(Optional.of(Map.of(
                "status", "DOWN",
                "components", Map.of("db", Map.of("status", "DOWN")))));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("health"))
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
                    assertThat(f.detail()).contains("db=DOWN");
                });
    }

    @Test
    void shouldFlagBlockedThreads() {
        when(client.metric(any(), eq("jvm.threads.states"), eq("state:blocked")))
                .thenReturn(Optional.of(gauge(12)));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("BLOCKED"))
                .singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("threaddump"));
    }

    @Test
    void shouldNotDivideByAnUnlimitedMaximum() {
        // process.files.max reports -1 when there is no limit; a ratio against it is meaningless.
        when(client.metric(any(), eq("process.files.open"), any())).thenReturn(Optional.of(gauge(500)));
        when(client.metric(any(), eq("process.files.max"), any())).thenReturn(Optional.of(gauge(-1)));

        assertThat(investigate()).noneSatisfy(
                f -> assertThat(f.title()).contains("file-descriptor"));
    }

    @Test
    void shouldCollapseATotallyUnreachableServiceIntoOneCriticalFinding() {
        // The bug this covers: a dead service used to produce ten INFO findings, which cannot
        // outrank a HIGH from the database - so the agent confidently blamed a missing index
        // while the service was not running at all.
        when(client.health(any())).thenThrow(new RuntimeException("Connection refused"));
        when(client.metric(any(), any(), any())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(investigate()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.title()).contains("unreachable");
            assertThat(f.detail()).contains("log file outlives the process");
        });
    }

    @Test
    void shouldNotCallItUnreachableWhenOnlyHealthIsSlow() {
        // A JVM thrashing in GC can time out on /health and still serve a metric a moment later.
        when(client.health(any())).thenThrow(new RuntimeException("Read timed out"));
        when(client.metric(any(), eq("jvm.memory.used"), eq("area:heap")))
                .thenReturn(Optional.of(gauge(10)));

        assertThat(investigate()).noneSatisfy(f -> assertThat(f.title()).contains("unreachable"));
    }

    @Test
    void shouldDegradeAFailedCheckToInfoRatherThanAbort() {
        // Health succeeds, so this is a single unavailable check rather than a dead service.
        when(client.metric(any(), eq("jvm.threads.states"), eq("state:blocked")))
                .thenThrow(new RuntimeException("connection refused"));

        assertThat(investigate())
                .filteredOn(f -> f.title().startsWith("Check unavailable"))
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.severity()).isEqualTo(Severity.INFO);
                    assertThat(f.detail()).contains("unknown, not as healthy");
                });
    }

    @Test
    void meanLatencyShouldBeDerivedFromCountAndTotalTime() {
        when(client.metric(any(), eq("http.server.requests")))
                .thenReturn(Optional.of(new ActuatorMetric("http.server.requests",
                        Map.of("COUNT", 100.0, "TOTAL_TIME", 120.0))));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("latency"))
                .singleElement()
                .satisfies(f -> assertThat(f.title()).contains("1200 ms"));
    }
}
