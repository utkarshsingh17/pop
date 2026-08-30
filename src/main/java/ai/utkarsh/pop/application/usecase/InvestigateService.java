package ai.utkarsh.pop.application.usecase;

import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.in.StartInvestigationUseCase;
import ai.utkarsh.pop.domain.port.out.DiagnosisEnginePort;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import ai.utkarsh.pop.infrastructure.config.InvestigationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * Orchestrates one investigation: open it, let the agent gather evidence and conclude, then
 * persist whatever state it ended in.
 *
 * <p>Deliberately <em>not</em> {@code @Transactional} around the agent call. That call makes
 * network round-trips to the model and can take minutes; holding a database transaction open
 * for its duration would pin a connection and risk timeouts. Each save is its own short
 * transaction instead.
 */
@Slf4j
@Service
public class InvestigateService implements StartInvestigationUseCase {

    private final InvestigationRepository repository;
    private final DiagnosisEnginePort diagnosisEngine;
    private final InvestigationProperties properties;
    private final Clock clock;

    InvestigateService(InvestigationRepository repository,
                       DiagnosisEnginePort diagnosisEngine,
                       InvestigationProperties properties,
                       Clock clock) {
        this.repository = repository;
        this.diagnosisEngine = diagnosisEngine;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Investigation start(StartInvestigationCommand command) {
        Duration lookback = command.lookback() != null ? command.lookback() : properties.defaultLookback();
        Investigation investigation = Investigation.open(
                command.question(),
                ServiceName.of(command.service()),
                TimeRange.lastly(lookback, clock.instant()),
                clock.instant());

        repository.save(investigation);
        log.info("Opened investigation {} for service '{}'", investigation.id(), investigation.service());

        investigation.begin(clock.instant());
        try {
            Diagnosis diagnosis = diagnosisEngine.diagnose(investigation);
            investigation.concludeWith(diagnosis, clock.instant());
            log.info("Investigation {} concluded: {} (confidence {})",
                    investigation.id(), diagnosis.probableRootCause(), diagnosis.confidence());
        } catch (RuntimeException e) {
            // The evidence gathered before the failure is still worth keeping — it is often
            // enough for a human to finish the job by hand.
            log.error("Investigation {} failed", investigation.id(), e);
            investigation.fail(e.getMessage(), clock.instant());
        }

        return repository.save(investigation);
    }
}
