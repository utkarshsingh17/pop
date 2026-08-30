package ai.utkarsh.pop.domain.model;

import java.util.Objects;

/**
 * Connection coordinates for a registered service's database.
 *
 * <p>The password is carried in plaintext <em>in memory only</em>. Encryption happens in the
 * persistence adapter, so the domain never depends on a cipher and stays testable without one.
 *
 * <p>{@link #toString()} is overridden because this record ends up in log lines and exception
 * messages; the generated one would print the password.
 */
public record DatabaseTarget(String jdbcUrl, String username, String password) {

    private static final int MAX_URL_LENGTH = 512;

    public DatabaseTarget {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        jdbcUrl = jdbcUrl.trim();
        if (jdbcUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("jdbcUrl must be at most " + MAX_URL_LENGTH + " characters");
        }
        // The investigators speak Postgres statistics views; another engine would return
        // nothing recognisable, so reject it at registration rather than at sweep time.
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException(
                    "only jdbc:postgresql:// URLs are supported, got: " + jdbcUrl);
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        username = username.trim();
        Objects.requireNonNull(password, "password must not be null (use an empty string if none)");
    }

    /** The same target with the secret stripped — what leaves the process in an API response. */
    public DatabaseTarget redacted() {
        return new DatabaseTarget(jdbcUrl, username, "");
    }

    @Override
    public String toString() {
        return "DatabaseTarget[jdbcUrl=%s, username=%s, password=***]".formatted(jdbcUrl, username);
    }
}
