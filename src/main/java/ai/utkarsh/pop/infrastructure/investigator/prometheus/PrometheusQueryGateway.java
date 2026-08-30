package ai.utkarsh.pop.infrastructure.investigator.prometheus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Retrying wrapper around {@link PrometheusApiClient}.
 *
 * <p>The retry lives here rather than on the investigator because Framework 7's resilience
 * support is proxy-based: a self-invoked method would bypass the advice entirely. Routing
 * every call through this separate bean guarantees it goes through the proxy.
 *
 * <p>Uses Framework 7 core resilience — no {@code spring-retry} dependency, and note the
 * attribute is {@code maxRetries} (retries <em>after</em> the first attempt), not
 * {@code maxAttempts}.
 */
@Slf4j
@Component
public class PrometheusQueryGateway {

    private final PrometheusApiClient client;

    PrometheusQueryGateway(PrometheusApiClient client) {
        this.client = client;
    }

    /**
     * Evaluates a PromQL expression, returning empty rather than throwing when Prometheus is
     * unreachable or reports an error — a missing metrics source should degrade an
     * investigation, not abort it.
     */
    @Retryable(includes = {IOException.class, RuntimeException.class},
            maxRetries = 2, delay = 200, multiplier = 2.0, maxDelay = 2000, jitter = 50)
    @ConcurrencyLimit(4)
    public Optional<Double> scalar(String promql) {
        PrometheusQueryResponse response = client.query(promql);
        if (response == null || !response.isSuccess()) {
            log.warn("Prometheus rejected query [{}]: {}", promql,
                    response == null ? "no response" : response.error());
            return Optional.empty();
        }
        return response.firstValue();
    }

    /** Raw response, for checks that need the per-series labels rather than one number. */
    @Retryable(includes = {IOException.class, RuntimeException.class},
            maxRetries = 2, delay = 200, multiplier = 2.0, maxDelay = 2000, jitter = 50)
    @ConcurrencyLimit(4)
    public PrometheusQueryResponse query(String promql) {
        return client.query(promql);
    }
}
