package ai.utkarsh.pop.infrastructure.investigator.prometheus;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative client for the Prometheus HTTP API.
 *
 * <p>No implementation and no {@code @Component}: Boot 4 registers the proxy from
 * {@code @ImportHttpServices(group = "prometheus")} on the application class, and the base URL
 * comes from {@code spring.http.serviceclient.prometheus.base-url}.
 */
@HttpExchange("/api/v1")
public interface PrometheusApiClient {

    /** Instant query — the value of an expression at a single point in time. */
    @GetExchange("/query")
    PrometheusQueryResponse query(@RequestParam("query") String query);

    /** Instant query evaluated at an explicit RFC 3339 timestamp. */
    @GetExchange("/query")
    PrometheusQueryResponse queryAt(@RequestParam("query") String query,
                                    @RequestParam("time") String time);
}
