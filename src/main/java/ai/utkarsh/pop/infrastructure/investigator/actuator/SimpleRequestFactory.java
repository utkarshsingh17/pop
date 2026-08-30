package ai.utkarsh.pop.infrastructure.investigator.actuator;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

/**
 * Per-request timeouts for actuator calls.
 *
 * <p>Separate from {@code spring.http.clients.*}, which configures the declarative Prometheus
 * client: an actuator on a struggling service deserves a tighter leash than a metrics backend.
 */
final class SimpleRequestFactory {

    private SimpleRequestFactory() {
    }

    static ClientHttpRequestFactory with(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
