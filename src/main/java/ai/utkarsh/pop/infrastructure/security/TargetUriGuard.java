package ai.utkarsh.pop.infrastructure.security;

import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

    TargetUriGuard(SecurityProperties properties) {
        this.allowedHosts = properties.allowedTargetHosts().stream()
                .filter(host -> !host.isBlank())
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** @throws IllegalArgumentException if the URL is malformed or the host is not permitted */
    public void requireAllowed(String jdbcUrl) {
        String host = hostOf(jdbcUrl);

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
