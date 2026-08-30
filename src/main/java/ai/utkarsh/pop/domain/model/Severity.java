package ai.utkarsh.pop.domain.model;

/** How alarming a single {@link Finding} is, independent of the overall diagnosis. */
public enum Severity {

    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
