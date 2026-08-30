package ai.utkarsh.pop.infrastructure.investigator.actuator;

import ai.utkarsh.pop.domain.model.ActuatorEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads Spring Boot Actuator on a registered service.
 *
 * <p>Deliberately not a declarative {@code @HttpExchange} interface like the Prometheus client:
 * that binds one base URL at startup, and here the address comes from the registry at call time.
 * A {@link RestClient} built per request is the price of that.
 *
 * <p>Timeouts are short and deliberate. pop is diagnosing a service that may already be
 * unhealthy — hanging on its actuator would turn a slow dependency into a stalled investigation.
 *
 * <p>Retries live on this bean rather than the investigator because Framework 7's resilience
 * support is proxy-based; a self-invoked method would bypass the advice entirely.
 */
@Slf4j
@Component
public class ActuatorClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient.Builder builder;

    ActuatorClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    /**
     * Fetches {@code /health}.
     *
     * <p>An unhealthy service answers 503 with a perfectly good body, so a non-2xx status is not
     * treated as a failure here — the body is what we came for.
     */
    @Retryable(includes = {IOException.class}, maxRetries = 1, delay = 200)
    @ConcurrencyLimit(4)
    public Optional<Map<String, Object>> health(ActuatorEndpoint endpoint) {
        return getMap(endpoint.baseUrl() + "/health");
    }

    /**
     * Fetches one metric, optionally narrowed by a tag such as {@code area:heap}.
     *
     * <p>Returns empty rather than throwing when the metric is absent: a service that does not
     * expose {@code hikaricp.connections.active} simply has no connection pool, which is not an
     * error and must not become a finding.
     */
    @Retryable(includes = {IOException.class}, maxRetries = 1, delay = 200)
    @ConcurrencyLimit(4)
    public Optional<ActuatorMetric> metric(ActuatorEndpoint endpoint, String name, String tag) {
        String url = endpoint.baseUrl() + "/metrics/" + name + (tag == null ? "" : "?tag=" + tag);
        return getMap(url).map(ActuatorMetric::from);
    }

    public Optional<ActuatorMetric> metric(ActuatorEndpoint endpoint, String name) {
        return metric(endpoint, name, null);
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> getMap(String url) {
        try {
            Map<String, Object> body = builder.clone()
                    .requestFactory(SimpleRequestFactory.with(CONNECT_TIMEOUT, READ_TIMEOUT))
                    .build()
                    .get()
                    .uri(url)
                    // 404 for a missing metric and 503 for a DOWN service are both answers,
                    // not transport failures.
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            return null;
                        }
                        return response.bodyTo(Map.class);
                    });
            return Optional.ofNullable(body);
        } catch (RuntimeException e) {
            log.debug("Actuator request to {} failed: {}", url, e.getMessage());
            throw e;
        }
    }

    /**
     * One actuator metric reading.
     *
     * <p>{@code /metrics/{name}} returns a list of measurements keyed by statistic — VALUE for a
     * gauge, COUNT and TOTAL_TIME for a timer — so a caller has to say which one it wants.
     */
    public record ActuatorMetric(String name, Map<String, Double> measurements) {

        @SuppressWarnings("unchecked")
        static ActuatorMetric from(Map<String, Object> body) {
            Object raw = body.get("measurements");
            Map<String, Double> values = new java.util.LinkedHashMap<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> measurement) {
                        Object statistic = measurement.get("statistic");
                        Object value = measurement.get("value");
                        if (statistic != null && value instanceof Number number) {
                            values.put(String.valueOf(statistic), number.doubleValue());
                        }
                    }
                }
            }
            return new ActuatorMetric(String.valueOf(body.get("name")), values);
        }

        public Optional<Double> statistic(String name) {
            return Optional.ofNullable(measurements.get(name));
        }

        public Optional<Double> value() {
            return statistic("VALUE");
        }
    }
}
