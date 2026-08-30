package ai.utkarsh.pop.domain.model;

/**
 * Which investigator produced a {@link Finding}.
 *
 * <p>Adding a new investigator (logs, Kubernetes, tracing) means adding a constant here
 * and one adapter implementing {@code InvestigatorPort} — the domain is otherwise untouched.
 */
public enum FindingSource {

    POSTGRES,
    PROMETHEUS
}
