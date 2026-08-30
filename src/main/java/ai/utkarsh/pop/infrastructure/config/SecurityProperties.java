package ai.utkarsh.pop.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Protection for the service registry.
 *
 * @param secretKey          base64-encoded AES key (16, 24 or 32 bytes) used to encrypt
 *                           registered database passwords at rest. Absent means registrations
 *                           carrying a password are refused — pop fails closed rather than
 *                           storing a secret it cannot protect.
 * @param allowedTargetHosts when non-empty, the only hosts a registration may point at. Empty
 *                           means any host except the always-blocked ranges (link-local,
 *                           wildcard, multicast).
 * @param allowedLogDirs     directories a file log source may live under. Empty refuses file log
 *                           sources outright: a path supplied over an API and read off pop's own
 *                           disk is a local-file-inclusion surface, so it fails closed the same
 *                           way an unset secret key does.
 */
@ConfigurationProperties(prefix = "pop.security")
public record SecurityProperties(
        String secretKey,
        @DefaultValue("") List<String> allowedTargetHosts,
        @DefaultValue("") List<String> allowedLogDirs) {

    public boolean hasSecretKey() {
        return secretKey != null && !secretKey.isBlank();
    }
}
