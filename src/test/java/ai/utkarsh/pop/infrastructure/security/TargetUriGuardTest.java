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
        return new TargetUriGuard(new SecurityProperties(null, List.of(allowedHosts)));
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
    void shouldEnforceTheAllowlistWhenOneIsConfigured() {
        TargetUriGuard guard = guardWith("db.internal");

        assertThatThrownBy(() -> guard.requireAllowed("jdbc:postgresql://localhost:5432/shop"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in pop.security.allowed-target-hosts");
    }
}
