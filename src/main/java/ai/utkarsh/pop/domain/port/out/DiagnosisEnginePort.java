package ai.utkarsh.pop.domain.port.out;

import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Investigation;

/**
 * Driven port: the reasoning engine that turns gathered evidence into a conclusion.
 *
 * <p>Deliberately says nothing about LLMs. The production adapter drives Claude through
 * Spring AI with tool calling; tests substitute a stub and never touch the network.
 */
public interface DiagnosisEnginePort {

    /**
     * Investigates and concludes.
     *
     * <p>The implementation is expected to call tools, and each tool call records findings
     * onto the supplied aggregate — so {@code investigation} is mutated during this call and
     * holds the evidence trail when it returns.
     *
     * @throws DiagnosisFailedException when no conclusion could be produced
     */
    Diagnosis diagnose(Investigation investigation);

    /** Thrown when the engine cannot produce a diagnosis at all. */
    class DiagnosisFailedException extends RuntimeException {

        public DiagnosisFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        public DiagnosisFailedException(String message) {
            super(message);
        }
    }
}
