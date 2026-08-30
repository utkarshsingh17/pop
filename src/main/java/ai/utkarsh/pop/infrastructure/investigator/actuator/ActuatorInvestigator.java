package ai.utkarsh.pop.infrastructure.investigator.actuator;

import ai.utkarsh.pop.domain.model.ActuatorEndpoint;
import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigatorPort;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Driven adapter: reads the running JVM of a registered service through Spring Boot Actuator.
 *
 * <p>Complements rather than replaces the Prometheus investigator. Actuator answers <em>what is
 * true right now</em> — this heap, these threads, this pool — read straight from the process,
 * with no scrape configuration and no delay. It cannot answer <em>when did this start</em>,
 * because the endpoint holds no history. Prometheus is the other half of that pair.
 *
 * <p>Every finding carries a concrete next step, because a number on its own ("heap is 92% full")
 * leaves the model to invent the remediation, and inventing is exactly what this platform exists
 * to avoid.
 *
 * <p>Only services registered with an actuator URL produce findings. An unregistered service
 * yields nothing at all rather than an error — there is simply no endpoint to ask.
 */
@Slf4j
@Component
public class ActuatorInvestigator implements InvestigatorPort {

    private final MonitoredServiceRepository services;
    private final ActuatorClient client;
    private final Clock clock;

    ActuatorInvestigator(MonitoredServiceRepository services, ActuatorClient client, Clock clock) {
        this.services = services;
        this.client = client;
        this.clock = clock;
    }

    @Override
    public FindingSource source() {
        return FindingSource.ACTUATOR;
    }

    @Override
    public List<Finding> investigate(ServiceName service, TimeRange range) {
        Optional<ActuatorEndpoint> endpoint = services.findByNameWithoutSecrets(service)
                .filter(MonitoredService::enabled)
                .flatMap(MonitoredService::actuator);

        if (endpoint.isEmpty()) {
            log.debug("No actuator endpoint registered for '{}'; skipping", service);
            return List.of();
        }

        ActuatorEndpoint actuator = endpoint.get();
        List<Finding> findings = new ArrayList<>();

        // A service that answers nothing at all is the headline, not a footnote. Confirm it with
        // one cheap second call and stop: the remaining checks would each pay a full connect
        // timeout to learn the same thing, and would bury the fact under ten INFO findings that
        // cannot outrank a HIGH from another source.
        Optional<Finding> unreachable = detectUnreachable(actuator);
        if (unreachable.isPresent()) {
            return List.of(unreachable.get());
        }

        health(actuator).ifPresent(findings::add);
        for (Check check : checks()) {
            evaluate(actuator, check).ifPresent(findings::add);
        }
        return findings;
    }

    /**
     * Distinguishes "nothing at this address answers" from "this one metric is not exposed".
     *
     * <p>Those are the same code path but very different facts. A service without a connection
     * pool legitimately has no {@code hikaricp} metrics and that is not a problem; a service whose
     * every endpoint refuses the connection has died, and that outranks anything the database has
     * to say.
     *
     * <p>Two probes rather than one, because a single slow response is not death: a JVM thrashing
     * in GC can time out on {@code /health} and still serve a metric a moment later.
     */
    private Optional<Finding> detectUnreachable(ActuatorEndpoint endpoint) {
        String firstError = probeFailure(() -> client.health(endpoint));
        if (firstError == null) {
            return Optional.empty();
        }
        String secondError = probeFailure(() -> client.metric(endpoint, "jvm.memory.used", "area:heap"));
        if (secondError == null) {
            return Optional.empty();
        }

        log.warn("Actuator at {} is unreachable: {}", endpoint.baseUrl(), secondError);
        return Optional.of(Finding.of(FindingSource.ACTUATOR, Severity.CRITICAL,
                "Service is unreachable at " + endpoint.baseUrl(),
                "Nothing answered at this address, so no runtime evidence could be gathered at "
                        + "all — treat every JVM check as unknown rather than healthy. A service "
                        + "that stops answering its own actuator has usually died or is wedged "
                        + "(an OutOfMemoryError and GC thrash both look like this). "
                        + "Check: whether the process is alive, and read its log for a fatal "
                        + "error — the log file outlives the process, this endpoint does not.",
                clock.instant(),
                Finding.evidenceOf("check", "reachability", "endpoint", endpoint.baseUrl(),
                        "error", secondError)));
    }

    /** @return the failure message, or null when the call succeeded */
    private static String probeFailure(Supplier<?> call) {
        try {
            call.get();
            return null;
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * {@code /health} is the one check that is not a number. A DOWN component names the broken
     * dependency outright, which is usually the fastest route to a root cause.
     */
    @SuppressWarnings("unchecked")
    private Optional<Finding> health(ActuatorEndpoint endpoint) {
        Map<String, Object> body;
        try {
            body = client.health(endpoint).orElse(null);
        } catch (RuntimeException e) {
            return Optional.of(unavailable("health", e.getMessage()));
        }
        if (body == null) {
            return Optional.empty();
        }

        String status = String.valueOf(body.getOrDefault("status", "UNKNOWN"));
        if ("UP".equalsIgnoreCase(status)) {
            return Optional.empty();
        }

        List<String> downComponents = new ArrayList<>();
        if (body.get("components") instanceof Map<?, ?> components) {
            components.forEach((name, detail) -> {
                if (detail instanceof Map<?, ?> map
                        && !"UP".equalsIgnoreCase(String.valueOf(map.get("status")))) {
                    downComponents.add(name + "=" + map.get("status"));
                }
            });
        }

        String failing = downComponents.isEmpty() ? "none reported" : String.join(", ", downComponents);
        return Optional.of(Finding.of(FindingSource.ACTUATOR,
                "DOWN".equalsIgnoreCase(status) ? Severity.CRITICAL : Severity.HIGH,
                "Service health is %s".formatted(status),
                "The service reports itself as not healthy. Failing components: %s. "
                        .formatted(failing)
                        + "Check: open /actuator/health on the service for the full component tree — "
                        + "a single DOWN dependency (database, disk, broker) is usually the cause "
                        + "and fixing it clears the rest.",
                clock.instant(),
                Finding.evidenceOf("check", "health", "status", status, "components", failing)));
    }

    private List<Check> checks() {
        return List.of(
                new Check("JVM heap usage",
                        e -> ratio(e, "jvm.memory.used", "area:heap", "jvm.memory.max", "area:heap"),
                        v -> v >= 0.95 ? Severity.CRITICAL : v >= 0.85 ? Severity.HIGH
                                : v >= 0.75 ? Severity.MEDIUM : null,
                        v -> "JVM heap is %.0f%% full".formatted(v * 100),
                        "Sustained high heap drives GC pressure and ends in OutOfMemoryError. "
                                + "Check: take a heap dump (/actuator/heapdump) and look for the "
                                + "dominant retained set; also compare against jvm.gc.pause — rising "
                                + "heap with rising GC time means a leak rather than a small max."),

                new Check("Metaspace usage",
                        e -> ratio(e, "jvm.memory.used", "area:nonheap", "jvm.memory.max", "area:nonheap"),
                        v -> v >= 0.95 ? Severity.HIGH : v >= 0.85 ? Severity.MEDIUM : null,
                        v -> "Non-heap memory is %.0f%% full".formatted(v * 100),
                        "Metaspace exhaustion throws OutOfMemoryError even with heap to spare. "
                                + "Check: classloader count and whether the application redeploys "
                                + "in place — leaked classloaders are the usual cause."),

                new Check("Blocked threads",
                        e -> gauge(e, "jvm.threads.states", "state:blocked"),
                        v -> v >= 10 ? Severity.HIGH : v >= 5 ? Severity.MEDIUM : null,
                        v -> "%.0f threads are BLOCKED".formatted(v),
                        "Threads blocked on a monitor are threads not serving requests. "
                                + "Check: take a thread dump (/actuator/threaddump) and look for many "
                                + "threads waiting on the same lock — that lock is the bottleneck."),

                new Check("Live threads",
                        e -> gauge(e, "jvm.threads.live", null),
                        v -> v >= 1000 ? Severity.HIGH : v >= 500 ? Severity.MEDIUM : null,
                        v -> "%.0f live threads".formatted(v),
                        "A large thread count usually means work is queueing on a slow downstream "
                                + "call rather than the service being busy. "
                                + "Check: thread dump for a common stack frame, and the latency of "
                                + "whatever those threads are waiting on."),

                new Check("Garbage collection pause",
                        e -> meanOf(e, "jvm.gc.pause"),
                        v -> v >= 0.5 ? Severity.HIGH : v >= 0.2 ? Severity.MEDIUM : null,
                        v -> "Mean GC pause is %.0f ms".formatted(v * 1000),
                        "Every pause is wall-clock time the service spends serving nobody. "
                                + "Check: heap usage first — long pauses with a full heap point at "
                                + "sizing or a leak, not at collector tuning."),

                new Check("CPU usage",
                        e -> gauge(e, "process.cpu.usage", null),
                        v -> v >= 0.95 ? Severity.HIGH : v >= 0.85 ? Severity.MEDIUM : null,
                        v -> "Process CPU usage is %.0f%%".formatted(v * 100),
                        "The service is CPU-bound; latency will rise with any additional load. "
                                + "Check: whether GC accounts for it (jvm.gc.pause) before assuming "
                                + "application code — a thrashing collector looks exactly like this."),

                new Check("Mean request latency",
                        e -> meanOf(e, "http.server.requests"),
                        v -> v >= 1.0 ? Severity.HIGH : v >= 0.5 ? Severity.MEDIUM : null,
                        v -> "Mean request latency is %.0f ms".formatted(v * 1000),
                        "This is the average since the process started, so a recent regression is "
                                + "diluted — treat it as a floor, not a current reading. "
                                + "Check: Prometheus for the p99 over a window, which is where a "
                                + "regression actually shows."),

                new Check("Connection pool usage",
                        e -> ratio(e, "hikaricp.connections.active", null, "hikaricp.connections.max", null),
                        v -> v >= 0.95 ? Severity.HIGH : v >= 0.80 ? Severity.MEDIUM : null,
                        v -> "Database connection pool is %.0f%% utilised".formatted(v * 100),
                        "Requests queue for a connection before they ever reach the database, so "
                                + "this shows up as latency with an idle database. "
                                + "Check: hikaricp.connections.pending, and the slow queries holding "
                                + "connections open — sweep the database next."),

                new Check("Open file descriptors",
                        e -> ratio(e, "process.files.open", null, "process.files.max", null),
                        v -> v >= 0.90 ? Severity.HIGH : v >= 0.80 ? Severity.MEDIUM : null,
                        v -> "%.0f%% of the file-descriptor limit is in use".formatted(v * 100),
                        "Exhausting descriptors makes every new socket and file fail at once. "
                                + "Check: for unclosed HTTP clients or streams, and the process ulimit."));
    }

    private Optional<Finding> evaluate(ActuatorEndpoint endpoint, Check check) {
        Optional<Double> observed;
        try {
            observed = check.read().apply(endpoint);
        } catch (RuntimeException e) {
            return Optional.of(unavailable(check.name(), e.getMessage()));
        }

        // A metric the service does not expose is not a finding. Inventing one from absent data
        // would be worse than silence.
        if (observed.isEmpty()) {
            return Optional.empty();
        }

        double value = observed.get();
        Severity severity = check.severityFor().apply(value);
        if (severity == null) {
            return Optional.empty();
        }

        return Optional.of(Finding.of(FindingSource.ACTUATOR, severity,
                check.titleFor().apply(value),
                check.suggestion(),
                clock.instant(),
                Finding.evidenceOf("check", check.name(), "value", String.valueOf(value))));
    }

    private Finding unavailable(String checkName, String error) {
        log.warn("Actuator check '{}' failed: {}", checkName, error);
        return Finding.of(FindingSource.ACTUATOR, Severity.INFO,
                "Check unavailable: " + checkName,
                "This check could not run: " + error
                        + ". Treat its absence as unknown, not as healthy.",
                clock.instant(),
                Finding.evidenceOf("check", checkName, "error", String.valueOf(error)));
    }

    private Optional<Double> gauge(ActuatorEndpoint endpoint, String metric, String tag) {
        return client.metric(endpoint, metric, tag).flatMap(ActuatorClient.ActuatorMetric::value);
    }

    /** Timers report COUNT and TOTAL_TIME; the mean is the only rate actuator alone can give. */
    private Optional<Double> meanOf(ActuatorEndpoint endpoint, String metric) {
        return client.metric(endpoint, metric).flatMap(m -> {
            double count = m.statistic("COUNT").orElse(0.0);
            double total = m.statistic("TOTAL_TIME").orElse(0.0);
            return count > 0 ? Optional.of(total / count) : Optional.empty();
        });
    }

    private Optional<Double> ratio(ActuatorEndpoint endpoint, String numerator, String numeratorTag,
                                   String denominator, String denominatorTag) {
        Optional<Double> used = gauge(endpoint, numerator, numeratorTag);
        Optional<Double> max = gauge(endpoint, denominator, denominatorTag);
        if (used.isEmpty() || max.isEmpty() || max.get() <= 0) {
            // A max of -1 means "unlimited" in several JVM metrics; a ratio against it is meaningless.
            return Optional.empty();
        }
        return Optional.of(used.get() / max.get());
    }

    /**
     * @param severityFor returns the severity for an observed value, or {@code null} when the
     *                    value is healthy and should not produce a finding
     * @param suggestion  what to do about it — carried into the finding's detail
     */
    private record Check(String name,
                         Function<ActuatorEndpoint, Optional<Double>> read,
                         DoubleFunction<Severity> severityFor,
                         DoubleFunction<String> titleFor,
                         String suggestion) {
    }
}
