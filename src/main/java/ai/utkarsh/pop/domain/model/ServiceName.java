package ai.utkarsh.pop.domain.model;

/**
 * The service under investigation, e.g. {@code order-service}.
 *
 * <p>This value reaches Prometheus as a label matcher, so it is constrained to the
 * characters Prometheus label values legitimately use. Anything else is rejected here
 * rather than being escaped at the adapter — an unconstrained name would let a caller
 * inject arbitrary PromQL through the label selector.
 */
public record ServiceName(String value) {

    private static final int MAX_LENGTH = 128;

    public ServiceName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("service name must be at most " + MAX_LENGTH + " characters");
        }
        if (!value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException(
                    "service name may only contain letters, digits, '_', '.', ':' and '-': " + value);
        }
    }

    public static ServiceName of(String value) {
        return new ServiceName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
