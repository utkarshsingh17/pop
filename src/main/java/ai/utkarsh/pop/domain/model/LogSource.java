package ai.utkarsh.pop.domain.model;

import java.util.Locale;

/**
 * Where a registered service's logs can be read.
 *
 * <p>The distinction that matters is whether the source outlives the process. A service's own
 * {@code /actuator/logfile} is served <em>by that process</em>, so at the moment you most need it
 * — the JVM died of an OutOfMemoryError — it refuses the connection along with everything else.
 * The file on disk still holds the stack trace.
 *
 * <p>So a file path is preferred where pop is co-located with the service, and the actuator
 * endpoint is the fallback for a remote one, useful for "errors are accumulating" but never for
 * a post-mortem.
 */
public record LogSource(Kind kind, String location) {

    private static final int MAX_LENGTH = 512;

    public enum Kind {
        /** A path on the same host as pop. Survives the service process. */
        FILE,
        /** The service's own {@code /actuator/logfile}. Dies with the service. */
        ACTUATOR
    }

    public LogSource {
        if (kind == null) {
            throw new IllegalArgumentException("log source kind must not be null");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("log source location must not be blank");
        }
        location = location.trim();
        if (location.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "log source location must be at most " + MAX_LENGTH + " characters");
        }
        if (kind == Kind.FILE && !location.startsWith("/")) {
            // A relative path would resolve against whatever directory pop happens to run in.
            throw new IllegalArgumentException("a file log source must be an absolute path: " + location);
        }
    }

    /** Infers the kind: an http(s) URL is the actuator endpoint, anything else a path. */
    public static LogSource of(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("log source location must not be blank");
        }
        String lower = location.trim().toLowerCase(Locale.ROOT);
        boolean url = lower.startsWith("http://") || lower.startsWith("https://");
        return new LogSource(url ? Kind.ACTUATOR : Kind.FILE, location);
    }

    public boolean survivesTheProcess() {
        return kind == Kind.FILE;
    }

    @Override
    public String toString() {
        return kind + ":" + location;
    }
}
