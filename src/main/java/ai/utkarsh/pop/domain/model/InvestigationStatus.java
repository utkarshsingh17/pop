package ai.utkarsh.pop.domain.model;

/**
 * Lifecycle of an {@link Investigation}.
 *
 * <pre>
 * PENDING ──begin()──▶ INVESTIGATING ──concludeWith()──▶ COMPLETED
 *    │                      │
 *    └────────fail()────────┴──────────────────────────▶ FAILED
 * </pre>
 */
public enum InvestigationStatus {

    PENDING,
    INVESTIGATING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
