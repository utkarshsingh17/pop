package ai.utkarsh.pop.domain.model;

/**
 * Which investigator produced a {@link Finding}.
 *
 * <p>Adding a new investigator (logs, Kubernetes, tracing) means adding a constant here
 * and one adapter implementing {@code InvestigatorPort} — the domain is otherwise untouched.
 */
public enum FindingSource {

    POSTGRES,
    PROMETHEUS,

    /**
     * Spring Boot Actuator on the service itself. Distinct from {@link #PROMETHEUS}: actuator is
     * a point-in-time reading taken directly from the process, whereas Prometheus holds history.
     * A finding from here says what is true now; one from there says when it changed.
     */
    ACTUATOR
}
