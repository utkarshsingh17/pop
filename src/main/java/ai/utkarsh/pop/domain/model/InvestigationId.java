package ai.utkarsh.pop.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@link Investigation}. Wraps the raw UUID so it cannot be confused
 * with any other identifier in a method signature.
 */
public record InvestigationId(UUID value) {

    public InvestigationId {
        Objects.requireNonNull(value, "investigation id must not be null");
    }

    public static InvestigationId generate() {
        return new InvestigationId(UUID.randomUUID());
    }

    public static InvestigationId of(String value) {
        return new InvestigationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
