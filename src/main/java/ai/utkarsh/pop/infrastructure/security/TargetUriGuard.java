package ai.utkarsh.pop.infrastructure.security;

import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Vets a JDBC URL before pop is willing to connect to it.
 *
 * <p>Once hosts arrive over an API rather than from configuration, the caller chooses what this
 * process connects to. That is a server-side request forgery surface: a registration pointing at
 * a link-local address reaches cloud instance metadata, and connection errors alone are enough to
 * map which internal ports are open. Registration is therefore the checkpoint.
 *
 * <p>Loopback and private ranges are deliberately <em>allowed</em> — a database on a private
 * network is the normal case, and blocking it would make the feature useless. Set
 * {@code pop.security.allowed-target-hosts} to narrow it to a known set.
 *
 * <p>Known limitation: the name is resolved here and again by the driver when it connects, so a
 * hostile DNS server could answer differently the second time. An allowlist of literal addresses
 * is the only complete answer to that, which is what the property is for.
 */
@Slf4j
@Component
public class TargetUriGuard {

    private final List<String> allowedHosts;
    private final List<Path> allowedLogDirs;

    TargetUriGuard(SecurityProperties properties) {
        this.allowedHosts = properties.allowedTargetHosts().stream()
                .filter(host -> !host.isBlank())
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .toList();
        // Canonicalised on both sides or the comparison is meaningless: on macOS /tmp is a
        // symlink to /private/tmp, so resolving only the candidate would make an allowed
        // directory of /tmp match nothing at all.
        this.allowedLogDirs = properties.allowedLogDirs().stream()
                .filter(dir -> !dir.isBlank())
                .map(dir -> canonical(Path.of(dir.trim())))
                .toList();
    }

    /**
     * Vets a log file path before pop opens it.
     *
     * <p>This one reads off pop's <em>own</em> disk from a location the caller chose, which is a
     * local-file-inclusion surface: unchecked, {@code /etc/passwd} or a private key is as readable
     * as a log. So it fails closed — with no {@code pop.security.allowed-log-dirs} configured,
     * every file source is refused — and the path is resolved and normalised before the
     * comparison, so {@code ../} cannot walk out of an allowed directory.
     *
     * @throws IllegalArgumentException if the path is outside every allowed directory
     */
    public void requireAllowedLogPath(String path) {
        if (allowedLogDirs.isEmpty()) {
            throw new IllegalArgumentException(
                    "File log sources are refused because pop.security.allowed-log-dirs is not "
                            + "configured. Set it to the directories pop may read logs from.");
        }

        Path resolved;
        try {
            resolved = canonical(Path.of(path));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a usable log file path: " + path);
        }

        boolean permitted = allowedLogDirs.stream().anyMatch(resolved::startsWith);
        if (!permitted) {
            throw new IllegalArgumentException(
                    "Log path '" + resolved + "' is not under any of pop.security.allowed-log-dirs");
        }
    }

    /** @throws IllegalArgumentException if the URL is malformed or the host is not permitted */
    public void requireAllowed(String jdbcUrl) {
        vetHost(hostOf(jdbcUrl));
    }

    /**
     * Vets an http(s) URL pop will fetch — the actuator endpoint of a registered service.
     *
     * <p>This is the sharper edge of the two. A JDBC URL at least has to find something speaking
     * the Postgres wire protocol; an HTTP GET will happily retrieve whatever is at the address,
     * which is exactly how cloud instance metadata gets exfiltrated. Same host rules, applied
     * before any request is made.
     */
    public void requireAllowedHttpUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("actuator URL must not be null");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed actuator URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("actuator URL must be http or https, got: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("actuator URL has no host: " + url);
        }
        vetHost(uri.getHost());
    }

    private void vetHost(String host) {
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Host '" + host + "' is not in pop.security.allowed-target-hosts");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve host '" + host + "'");
        }

        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress()) {
                // 169.254.0.0/16 — cloud instance metadata lives here.
                throw new IllegalArgumentException(
                        "Host '" + host + "' resolves to a link-local address, which is not permitted");
            }
            if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException(
                        "Host '" + host + "' resolves to a wildcard or multicast address, "
                                + "which is not permitted");
            }
        }
    }

    /**
     * Absolute, normalised, and symlink-resolved where the path exists — so {@code ../} cannot
     * walk out of an allowed directory and a symlink cannot point out of one either.
     */
    private static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (absolute.toFile().exists()) {
                return absolute.toRealPath();
            }
            // A log file that does not exist yet still has to be comparable, so canonicalise the
            // directory it will live in. Normalisation above already collapsed any ../, so this
            // cannot be used to escape an allowed directory.
            Path parent = absolute.getParent();
            if (parent != null && parent.toFile().exists()) {
                return parent.toRealPath().resolve(absolute.getFileName());
            }
            return absolute;
        } catch (IOException e) {
            return absolute;
        }
    }

    private static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("Not a jdbc:postgresql:// URL: " + jdbcUrl);
        }
        // Multi-host failover URLs would need every host vetted; refuse rather than
        // half-check one of them.
        String authority = jdbcUrl.substring("jdbc:postgresql://".length());
        if (authority.contains(",")) {
            throw new IllegalArgumentException(
                    "Multi-host JDBC URLs are not supported; register one host per service");
        }
        URI uri;
        try {
            // Strip the jdbc: prefix so this parses as a normal hierarchical URI.
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed JDBC URL: " + jdbcUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("JDBC URL has no host: " + jdbcUrl);
        }
        return host;
    }
}
