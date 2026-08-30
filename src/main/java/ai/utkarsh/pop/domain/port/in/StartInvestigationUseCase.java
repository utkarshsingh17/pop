package ai.utkarsh.pop.domain.port.in;

import ai.utkarsh.pop.domain.model.Investigation;

import java.time.Duration;

/** Driving port: begin a new investigation and run it to a conclusion. */
public interface StartInvestigationUseCase {

    Investigation start(StartInvestigationCommand command);

    /**
     * @param question the operator's natural-language question
     * @param service  the service to investigate
     * @param lookback how far back to gather evidence
     */
    record StartInvestigationCommand(String question, String service, Duration lookback) {

        public StartInvestigationCommand {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            if (lookback == null) {
                lookback = Duration.ofHours(1);
            }
        }
    }
}
