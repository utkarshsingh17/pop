package ai.utkarsh.pop.infrastructure.security;

import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetUriGuardTest {

    private static TargetUriGuard guardWith(String... allowedHosts) {
        return new TargetUriGuard(new SecurityProperties(null, List.of(allowedHosts), List.of()));
    }

    private static TargetUriGuard guardWithLogDirs(String... dirs) {
        return new TargetUriGuard(new SecurityProperties(null, List.of(), List.of(dirs)));
    }

    @Test
    void shouldRefuseFileLogSourcesWhenNoDirectoriesAreAllowed() {
        // Fails closed: a path supplied over an API and read off pop's own disk is a
        // local-file-inclusion surface, so it is refused until explicitly permitted.
        assertThatThrownBy(() -> guardWithLogDirs().requireAllowedLogPath("/tmp/app.log"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-log-dirs is not");
    }

    @Test
    void shouldAllowAPathUnderAnAllowedDirectory() {
        assertThatCode(() -> guardWithLogDirs("/tmp").requireAllowedLogPath("/tmp/app.log"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldCanonicaliseTheAllowlistAsWellAsTheCandidate() {
        // On macOS /tmp is a symlink to /private/tmp. Resolving only the candidate made an
        // allowed directory of /tmp match nothing, which is how this was found.
        assertThatCode(() -> guardWithLogDirs("/tmp").requireAllowedLogPath("/private/tmp/app.log"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guardWithLogDirs("/private/tmp").requireAllowedLogPath("/tmp/app.log"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectTraversalOutOfAnAllowedDirectory() {
        assertThatThrownBy(() -> guardWithLogDirs("/tmp")
                .requireAllowedLogPath("/tmp/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not under any of");
    }

    @Test
    void shouldRejectAPathOutsideEveryAllowedDirectory() {
        assertThatThrownBy(() -> guardWithLogDirs("/tmp").requireAllowedLogPath("/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not under any of");
    }

    @Test
    void shouldAllowAnOrdinaryLoopbackTarget() {
        // A database on localhost or a private network is the normal case, not an attack.
        assertThatCode(() -> guardWith().requireAllowed("jdbc:postgresql://localhost:5432/shop"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectLinkLocalAddresses() {
        // 169.254.169.254 is cloud instance metadata.
        assertThatThrownBy(() -> guardWith()
                .requireAllowed("jdbc:postgresql://169.254.169.254:5432/shop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-local");
    }

    @Test
    void shouldRejectTheWildcardAddress() {
        assertThatThrownBy(() -> guardWith().requireAllowed("jdbc:postgresql://0.0.0.0:5432/shop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://localhost:3306/shop",
            "http://localhost:5432/shop",
            "jdbc:postgresql:///shop",
            "not a url"
    })
    void shouldRejectAnythingThatIsNotAPostgresJdbcUrlWithAHost(String url) {
        assertThatThrownBy(() -> guardWith().requireAllowed(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMultiHostUrlsRatherThanVetOnlyOne() {
        assertThatThrownBy(() -> guardWith()
                .requireAllowed("jdbc:postgresql://localhost:5432,evil.example:5432/shop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multi-host");
    }

    @Test
    void shouldRejectLinkLocalActuatorUrls() {
        // The sharp edge: an HTTP GET retrieves whatever is at the address, which is how cloud
        // instance metadata gets exfiltrated.
        assertThatThrownBy(() -> guardWith()
                .requireAllowedHttpUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link-local");
    }

    @Test
    void shouldAllowAnOrdinaryActuatorUrl() {
        assertThatCode(() -> guardWith().requireAllowedHttpUrl("http://localhost:3001/actuator"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://localhost:3001", "ftp://x/y", "localhost:3001"})
    void shouldRejectNonHttpSchemesForActuator(String url) {
        assertThatThrownBy(() -> guardWith().requireAllowedHttpUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldEnforceTheAllowlistForActuatorUrlsToo() {
        assertThatThrownBy(() -> guardWith("db.internal")
                .requireAllowedHttpUrl("http://localhost:3001/actuator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in pop.security.allowed-target-hosts");
    }

    @Test
    void shouldEnforceTheAllowlistWhenOneIsConfigured() {
        TargetUriGuard guard = guardWith("db.internal");

        assertThatThrownBy(() -> guard.requireAllowed("jdbc:postgresql://localhost:5432/shop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in pop.security.allowed-target-hosts");
    }
}
