package ai.utkarsh.pop.infrastructure.investigator.prometheus;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleFunction;

/**
 * Driven adapter: gathers evidence from Prometheus.
 *
 * <p>Checks are declared as data ({@link Check}) rather than written out one by one, so adding
 * a signal is a single list entry. Each check is a PromQL expression, a threshold, and a
 * function turning the observed value into a {@link Severity}.
 *
 * <p>Metric names follow Micrometer / Spring Boot Actuator conventions. Series are matched on
 * a {@code service} label; {@link ServiceName} already constrains its characters, so the label
 * matcher cannot be used to smuggle extra PromQL.
 */
@Slf4j
@Component
public class PrometheusInvestigator implements InvestigatorPort {

    /** Rate window used by every rate() expression. */
    private static final String WINDOW = "5m";

    private final PrometheusQueryGateway gateway;
    private final Clock clock;

    PrometheusInvestigator(PrometheusQueryGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public FindingSource source() {
        return FindingSource.PROMETHEUS;
    }

    @Override
    public List<Finding> investigate(ServiceName service, TimeRange range) {
        String selector = "{service=\"" + service.value() + "\"}";
        String innerSelector = "service=\"" + service.value() + "\"";

        List<Check> checks = List.of(
                new Check(
                        "Service availability",
                        "min(up" + selector + ")",
                        value -> value < 1 ? Severity.CRITICAL : null,
                        value -> "Service reports as DOWN in Prometheus (up=%.0f)".formatted(value),
                        "At least one scrape target for this service is not responding."),

                new Check(
                        "Request latency (p99)",
                        "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{"
                                + innerSelector + "}[" + WINDOW + "])))",
                        value -> {
                            if (value >= 2.0) {
                                return Severity.CRITICAL;
                            }
                            if (value >= 1.0) {
                                return Severity.HIGH;
                            }
                            return value >= 0.5 ? Severity.MEDIUM : null;
                        },
                        value -> "p99 request latency is %.2f s".formatted(value),
                        "The slowest 1% of requests are well above a healthy budget."),

                new Check(
                        "Error rate",
                        "sum(rate(http_server_requests_seconds_count{" + innerSelector
                                + ",outcome=\"SERVER_ERROR\"}[" + WINDOW + "])) / "
                                + "clamp_min(sum(rate(http_server_requests_seconds_count{" + innerSelector
                                + "}[" + WINDOW + "])), 0.001)",
                        value -> {
                            if (value >= 0.10) {
                                return Severity.CRITICAL;
                            }
                            if (value >= 0.05) {
                                return Severity.HIGH;
                            }
                            return value >= 0.01 ? Severity.MEDIUM : null;
                        },
                        value -> "%.1f%% of requests are returning 5xx".formatted(value * 100),
                        "Server-side errors are a meaningful fraction of traffic."),

                new Check(
                        "JVM heap pressure",
                        "max(jvm_memory_used_bytes{" + innerSelector + ",area=\"heap\"}) / "
                                + "clamp_min(max(jvm_memory_max_bytes{" + innerSelector
                                + ",area=\"heap\"}), 1)",
                        value -> {
                            if (value >= 0.95) {
                                return Severity.CRITICAL;
                            }
                            return value >= 0.85 ? Severity.HIGH : null;
                        },
                        value -> "JVM heap is %.0f%% full".formatted(value * 100),
                        "Sustained high heap usage drives GC pressure and eventually OOM."),

                new Check(
                        "GC pause time",
                        "sum(rate(jvm_gc_pause_seconds_sum{" + innerSelector + "}[" + WINDOW + "]))",
                        value -> {
                            if (value >= 0.20) {
                                return Severity.HIGH;
                            }
                            return value >= 0.10 ? Severity.MEDIUM : null;
                        },
                        value -> "GC is consuming %.0f%% of wall-clock time".formatted(value * 100),
                        "Time spent paused for garbage collection is time not serving requests."),

                new Check(
                        "CPU saturation",
                        "max(system_cpu_usage{" + innerSelector + "})",
                        value -> {
                            if (value >= 0.95) {
                                return Severity.HIGH;
                            }
                            return value >= 0.85 ? Severity.MEDIUM : null;
                        },
                        value -> "Host CPU usage is at %.0f%%".formatted(value * 100),
                        "The service is CPU-bound; latency will rise with any additional load."),

                new Check(
                        "Connection pool saturation",
                        "max(hikaricp_connections_active{" + innerSelector + "}) / "
                                + "clamp_min(max(hikaricp_connections_max{" + innerSelector + "}), 1)",
                        value -> {
                            if (value >= 0.95) {
                                return Severity.HIGH;
                            }
                            return value >= 0.80 ? Severity.MEDIUM : null;
                        },
                        value -> "Database connection pool is %.0f%% utilised".formatted(value * 100),
                        "Requests will start queueing for a connection before they reach the database."));

        List<Finding> findings = new ArrayList<>();
        for (Check check : checks) {
            evaluate(check).ifPresent(findings::add);
        }
        return findings;
    }

    /**
     * Runs one check. A query that fails or returns no series yields no finding — Prometheus
     * legitimately has no data for a metric the service does not expose, and inventing a
     * finding from absent data would be worse than silence.
     */
    private Optional<Finding> evaluate(Check check) {
        Optional<Double> observed;
        try {
            observed = gateway.scalar(check.promql());
        } catch (RuntimeException e) {
            log.warn("Prometheus check '{}' failed: {}", check.name(), e.getMessage());
            return Optional.of(Finding.of(FindingSource.PROMETHEUS, Severity.INFO,
                    "Check unavailable: " + check.name(),
                    "Prometheus could not be queried: " + e.getMessage()
                            + ". Treat its absence as unknown, not as healthy.",
                    clock.instant(),
                    Finding.evidenceOf("check", check.name(), "query", check.promql(),
                            "error", String.valueOf(e.getMessage()))));
        }

        if (observed.isEmpty()) {
            return Optional.empty();
        }

        double value = observed.get();
        Severity severity = check.severityFor().apply(value);
        if (severity == null) {
            return Optional.empty();     // within healthy bounds
        }

        return Optional.of(Finding.of(FindingSource.PROMETHEUS, severity,
                check.titleFor().apply(value),
                check.explanation(),
                clock.instant(),
                Finding.evidenceOf(
                        "check", check.name(),
                        "query", check.promql(),
                        "value", String.valueOf(value))));
    }

    /**
     * One metric check.
     *
     * @param severityFor returns the severity for an observed value, or {@code null} when the
     *                    value is healthy and should not produce a finding
     */
    private record Check(String name,
                         String promql,
                         DoubleFunction<Severity> severityFor,
                         DoubleFunction<String> titleFor,
                         String explanation) {
    }
}
