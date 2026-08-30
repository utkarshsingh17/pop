package ai.utkarsh.pop.infrastructure.investigator.postgres;

/** Thrown when SQL submitted for analysis is not provably read-only. */
public class UnsafeSqlException extends RuntimeException {

    public UnsafeSqlException(String message) {
        super(message);
    }
}
