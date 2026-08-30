package ai.utkarsh.pop.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActuatorEndpointTest {

    @ParameterizedTest
    @CsvSource({
            "http://localhost:3001,          http://localhost:3001/actuator",
            "http://localhost:3001/,         http://localhost:3001/actuator",
            "http://localhost:3001/actuator, http://localhost:3001/actuator",
            "http://localhost:3001/actuator/,http://localhost:3001/actuator",
            "https://svc.internal,           https://svc.internal/actuator"
    })
    void shouldNormaliseToTheActuatorBase(String input, String expected) {
        // Both forms are things an operator reasonably types; guessing wrong yields 404s that
        // look like the service is broken.
        assertThat(new ActuatorEndpoint(input).baseUrl()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "http://localhost:3001, localhost-3001",
            "http://order-svc:8080, order-svc-8080",
            "https://svc.internal,  svc.internal"
    })
    void shouldDeriveAServiceNameFromHostAndPort(String url, String expected) {
        String derived = new ActuatorEndpoint(url).deriveServiceName();

        assertThat(derived).isEqualTo(expected);
        // Must survive ServiceName's charset, since it is used as one.
        assertThat(ServiceName.of(derived).value()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://localhost:3001", "localhost:3001", "jdbc:postgresql://x/y", "  "})
    void shouldRejectAnythingThatIsNotAnHttpUrl(String url) {
        assertThatThrownBy(() -> new ActuatorEndpoint(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectAUrlWithNoHost() {
        assertThatThrownBy(() -> new ActuatorEndpoint("http:///actuator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host");
    }
}
