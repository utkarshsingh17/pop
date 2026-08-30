package ai.utkarsh.pop.domain.model;

import java.net.URI;
import java.util.Locale;

/**
 * Where a registered service exposes Spring Boot Actuator.
 *
 * <p>Callers register the service root — {@code http://localhost:3001} — and this normalises it
 * to the actuator base. Accepting either form matters because both are things an operator
 * reasonably types, and guessing wrong produces 404s that look like the service is broken.
 *
 * <p>Only the shape is validated here. Whether the host is one pop is permitted to reach is a
 * different question, answered by the infrastructure guard before any request is made — the
 * domain has no business resolving DNS.
 */
public record ActuatorEndpoint(String baseUrl) {

    private static final int MAX_LENGTH = 512;

    public ActuatorEndpoint {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("actuator URL must not be blank");
        }
        baseUrl = normalise(baseUrl.trim());
        if (baseUrl.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("actuator URL must be at most " + MAX_LENGTH + " characters");
        }
    }

    /** {@code http://host:3001} and {@code http://host:3001/actuator/} both end up identical. */
    private static String normalise(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "actuator URL must start with http:// or https://, got: " + raw);
        }

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed actuator URL: " + raw);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("actuator URL has no host: " + raw);
        }

        String trimmed = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        return trimmed.toLowerCase(Locale.ROOT).endsWith("/actuator") ? trimmed : trimmed + "/actuator";
    }

    /**
     * A service name derived from the endpoint, for callers who register a URL and nothing else.
     *
     * <p>Uses {@code -} rather than {@code :} between host and port: both are legal in a
     * {@link ServiceName}, but the name becomes a path segment in
     * {@code /api/v1/services/{name}} and a colon there is needless friction.
     */
    public String deriveServiceName() {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost().replaceAll("[^A-Za-z0-9_.-]", "-");
        return uri.getPort() > 0 ? host + "-" + uri.getPort() : host;
    }

    @Override
    public String toString() {
        return baseUrl;
    }
}
