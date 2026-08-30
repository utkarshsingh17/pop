package ai.utkarsh.pop.application.usecase;

import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationStatus;
import ai.utkarsh.pop.domain.port.in.StartInvestigationUseCase.StartInvestigationCommand;
import ai.utkarsh.pop.domain.port.out.DiagnosisEnginePort;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import ai.utkarsh.pop.infrastructure.config.InvestigationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestigateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Mock
    private InvestigationRepository repository;

    @Mock
    private DiagnosisEnginePort diagnosisEngine;

    private InvestigateService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(Investigation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new InvestigateService(repository, diagnosisEngine,
                new InvestigationProperties(Duration.ofHours(1), false),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StartInvestigationCommand command(Duration lookback) {
        return new StartInvestigationCommand("why is it slow?", "order-service", lookback);
    }

    private static Diagnosis diagnosis() {
        return new Diagnosis("missing index", Confidence.HIGH, "summary",
                List.of("CREATE INDEX ..."), List.of("Slow query"));
    }

    @Test
    void start_whenEngineSucceeds_shouldCompleteWithDiagnosis() {
        when(diagnosisEngine.diagnose(any())).thenReturn(diagnosis());

        Investigation result = service.start(command(Duration.ofHours(2)));

        assertThat(result.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(result.diagnosis()).contains(diagnosis());
        assertThat(result.completedAt()).contains(NOW);
    }

    @Test
    void start_shouldPersistBeforeAndAfterTheAgentRuns() {
        when(diagnosisEngine.diagnose(any())).thenReturn(diagnosis());

        service.start(command(null));

        // Saved once on open (so a crashed run still leaves a record) and once at the end.
        verify(repository, times(2)).save(any(Investigation.class));
    }

    @Test
    void start_shouldUseConfiguredLookbackWhenNoneGiven() {
        when(diagnosisEngine.diagnose(any())).thenReturn(diagnosis());

        Investigation result = service.start(command(null));

        assertThat(result.timeRange().duration()).isEqualTo(Duration.ofHours(1));
        assertThat(result.timeRange().to()).isEqualTo(NOW);
    }

    @Test
    void start_shouldHonourExplicitLookback() {
        when(diagnosisEngine.diagnose(any())).thenReturn(diagnosis());

        Investigation result = service.start(command(Duration.ofMinutes(30)));

        assertThat(result.timeRange().duration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void start_whenEngineFails_shouldMarkFailedRatherThanPropagate() {
        when(diagnosisEngine.diagnose(any()))
                .thenThrow(new DiagnosisEnginePort.DiagnosisFailedException("model unavailable"));

        Investigation result = service.start(command(null));

        assertThat(result.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(result.failureReason()).contains("model unavailable");
    }

    @Test
    void start_whenEngineFails_shouldStillPersistTheFailedInvestigation() {
        when(diagnosisEngine.diagnose(any())).thenThrow(new IllegalStateException("boom"));

        service.start(command(null));

        ArgumentCaptor<Investigation> captor = ArgumentCaptor.forClass(Investigation.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InvestigationStatus.FAILED);
    }

    @Test
    void start_shouldHandInvestigatingAggregateToTheEngine() {
        ArgumentCaptor<Investigation> captor = ArgumentCaptor.forClass(Investigation.class);
        when(diagnosisEngine.diagnose(captor.capture())).thenReturn(diagnosis());

        service.start(command(null));

        // The engine's tool calls record findings, which is only legal while INVESTIGATING.
        assertThat(captor.getValue().startedAt()).contains(NOW);
    }
}
