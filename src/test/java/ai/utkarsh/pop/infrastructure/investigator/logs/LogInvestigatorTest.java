package ai.utkarsh.pop.infrastructure.investigator.logs;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.LogSource;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogInvestigatorTest {

    private static final ServiceName SERVICE = ServiceName.of("order-service");
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final LogSource FILE = new LogSource(LogSource.Kind.FILE, "/tmp/order-service.log");

    @Mock
    private MonitoredServiceRepository services;

    @Mock
    private LogReader reader;

    private LogInvestigator investigator;

    @BeforeEach
    void setUp() {
        investigator = new LogInvestigator(services, reader, Clock.fixed(NOW, ZoneOffset.UTC));
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.of(
                MonitoredService.register(SERVICE, null, null, null, FILE, NOW)));
    }

    private List<Finding> investigate() {
        return investigator.investigate(SERVICE, TimeRange.lastly(Duration.ofHours(1), NOW));
    }

    @Test
    void shouldReportTheLogsSource() {
        assertThat(investigator.source()).isEqualTo(FindingSource.LOGS);
    }

    @Test
    void shouldProduceNothingWhenNoLogSourceIsRegistered() {
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.of(
                MonitoredService.register(SERVICE, null, null, null, null, NOW)));

        assertThat(investigate()).isEmpty();
    }

    @Test
    void shouldSurfaceAnOutOfMemoryErrorAsCritical() {
        // The case the whole adapter exists for: the JVM died, every actuator check reports
        // connection refused, and this stack trace on disk is the only surviving explanation.
        when(reader.tail(any())).thenReturn("""
                2026-08-30T07:04:54 ERROR --- [http-nio-8081-exec-5] o.a.c.c.C.[dispatcherServlet] : Servlet threw
                java.lang.OutOfMemoryError: Java heap space
                	at demo.LoadController.leak(LoadController.java:38)
                """);

        assertThat(investigate())
                .filteredOn(f -> f.severity() == Severity.CRITICAL)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.title()).contains("OutOfMemoryError");
                    assertThat(f.detail()).contains("ran out of heap");
                });
    }

    @Test
    void shouldClusterRepeatingExceptionsRatherThanListThem() {
        String line = "2026-08-30T12:00:00 ERROR --- x : java.lang.NullPointerException: boom\n";
        when(reader.tail(any())).thenReturn(line.repeat(12));

        assertThat(investigate())
                .filteredOn(f -> f.title().contains("NullPointerException"))
                .singleElement()
                .satisfies(f -> assertThat(f.title()).contains("12 times"));
    }

    @Test
    void shouldNotClusterAnExceptionSeenOnlyOnce() {
        when(reader.tail(any())).thenReturn(
                "2026-08-30T12:00:00 WARN --- x : java.lang.IllegalStateException: once\n");

        assertThat(investigate()).noneSatisfy(
                f -> assertThat(f.title()).contains("IllegalStateException appears"));
    }

    @Test
    void shouldStaySilentOnAQuietLog() {
        when(reader.tail(any())).thenReturn("""
                2026-08-30T12:00:00 INFO --- x : Started DemoServiceApplication in 1.2 seconds
                2026-08-30T12:00:01 INFO --- x : Tomcat started on port 8081
                """);

        assertThat(investigate()).isEmpty();
    }

    @Test
    void shouldDegradeAnUnreadableLogToInfo() {
        when(reader.tail(any())).thenThrow(new IllegalStateException("Log file is not readable"));

        assertThat(investigate()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.detail()).contains("unknown, not as healthy");
        });
    }

    @Test
    void shouldPointOutWhenTheSourceCannotSurviveTheProcess() {
        when(services.findByNameWithoutSecrets(SERVICE)).thenReturn(Optional.of(MonitoredService.register(
                SERVICE, null, null, null,
                new LogSource(LogSource.Kind.ACTUATOR, "http://localhost:8081/actuator/logfile"), NOW)));
        when(reader.tail(any())).thenThrow(new IllegalStateException("Connection refused"));

        assertThat(investigate()).singleElement().satisfies(f ->
                assertThat(f.detail()).contains("a file path would survive it"));
    }
}
