package ai.utkarsh.pop.infrastructure.investigator.logs;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.LogSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Driven adapter: reads a registered service's log output.
 *
 * <p>The only source that outlives the process it describes. When a JVM dies of an
 * OutOfMemoryError, every actuator check reports connection refused and the metrics go dark — but
 * the stack trace is already on disk. That is precisely the incident where logs are the whole
 * answer, and it is why a file source is preferred over the service's own
 * {@code /actuator/logfile}, which dies along with it.
 *
 * <p>Errors are clustered by exception type rather than reported line by line: forty-seven copies
 * of the same NullPointerException are one finding, not forty-seven, and the count is the
 * interesting part.
 */
@Slf4j
@Component
public class LogInvestigator implements InvestigatorPort {

    /** Fatal conditions worth surfacing above any error-rate arithmetic. */
    private static final Map<String, String> FATAL = Map.of(
            "OutOfMemoryError", "The JVM ran out of heap. Anything else in this investigation is "
                    + "probably a symptom of it, including checks that report as unavailable.",
            "StackOverflowError", "Unbounded recursion. The stack trace names the cycle.",
            "NoClassDefFoundError", "A class was missing at runtime — usually a dependency that is "
                    + "present at compile time but not packaged.",
            "OutOfMemoryError: Metaspace", "Metaspace exhausted, typically leaked classloaders.");

    private static final Pattern EXCEPTION = Pattern.compile(
            "\\b((?:[a-z][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*(?:Exception|Error))\\b");
    private static final Pattern LEVEL = Pattern.compile("\\b(ERROR|WARN)\\b");

    private static final int ERROR_COUNT_HIGH = 50;
    private static final int ERROR_COUNT_MEDIUM = 10;

    private final MonitoredServiceRepository services;
    private final LogReader reader;
    private final Clock clock;

    LogInvestigator(MonitoredServiceRepository services, LogReader reader, Clock clock) {
        this.services = services;
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public FindingSource source() {
        return FindingSource.LOGS;
    }

    @Override
    public List<Finding> investigate(ServiceName service, TimeRange range) {
        Optional<LogSource> configured = services.findByNameWithoutSecrets(service)
                .filter(MonitoredService::enabled)
                .flatMap(MonitoredService::logSource);

        if (configured.isEmpty()) {
            log.debug("No log source registered for '{}'; skipping", service);
            return List.of();
        }

        LogSource logSource = configured.get();
        String tail;
        try {
            tail = reader.tail(logSource);
        } catch (RuntimeException e) {
            log.warn("Could not read logs for '{}': {}", service, e.getMessage());
            return List.of(Finding.of(FindingSource.LOGS, Severity.INFO,
                    "Check unavailable: log tail",
                    "The log could not be read: " + e.getMessage()
                            + ". Treat its absence as unknown, not as healthy."
                            + (logSource.survivesTheProcess() ? ""
                            : " This source is the service's own actuator endpoint, which stops "
                            + "answering when the service does — a file path would survive it."),
                    clock.instant(),
                    Finding.evidenceOf("source", logSource.toString(),
                            "error", String.valueOf(e.getMessage()))));
        }

        if (tail.isBlank()) {
            return List.of();
        }
        return analyse(tail, logSource);
    }

    private List<Finding> analyse(String tail, LogSource logSource) {
        List<Finding> findings = new ArrayList<>();
        List<String> lines = tail.lines().toList();

        int errors = 0;
        int warnings = 0;
        Map<String, Integer> exceptions = new LinkedHashMap<>();
        String lastFatalLine = null;
        String fatalKind = null;

        for (String line : lines) {
            Matcher level = LEVEL.matcher(line);
            if (level.find()) {
                if ("ERROR".equals(level.group(1))) {
                    errors++;
                } else {
                    warnings++;
                }
            }
            Matcher exception = EXCEPTION.matcher(line);
            while (exception.find()) {
                String type = exception.group(1);
                exceptions.merge(simpleName(type), 1, Integer::sum);
            }
            for (String fatal : FATAL.keySet()) {
                if (line.contains(fatal)) {
                    lastFatalLine = line.strip();
                    fatalKind = fatal;
                }
            }
        }

        // A fatal error outranks any rate: if the JVM died, that is the finding.
        if (fatalKind != null) {
            findings.add(Finding.of(FindingSource.LOGS, Severity.CRITICAL,
                    "Fatal error in the log: " + fatalKind,
                    FATAL.get(fatalKind) + " Check: the full stack trace around this line, and "
                            + "whether the process is still alive. "
                            + "Last occurrence: " + truncate(lastFatalLine),
                    clock.instant(),
                    Finding.evidenceOf("fatal", fatalKind, "line", truncate(lastFatalLine),
                            "source", logSource.toString())));
        }

        if (errors >= ERROR_COUNT_MEDIUM) {
            findings.add(Finding.of(FindingSource.LOGS,
                    errors >= ERROR_COUNT_HIGH ? Severity.HIGH : Severity.MEDIUM,
                    "%d ERROR lines in the last %d KB of log".formatted(errors, LogReader.TAIL_BYTES / 1024),
                    "Errors are accumulating. Check: the clustered exception types below — one "
                            + "type dominating usually means a single broken dependency rather "
                            + "than widespread failure.",
                    clock.instant(),
                    Finding.evidenceOf("errors", String.valueOf(errors),
                            "warnings", String.valueOf(warnings),
                            "source", logSource.toString())));
        }

        // Cluster rather than list: forty-seven copies of one trace is one fact.
        exceptions.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> findings.add(Finding.of(FindingSource.LOGS, Severity.MEDIUM,
                        "%s appears %d times".formatted(e.getKey(), e.getValue()),
                        "A repeating exception type. Check: whether every occurrence shares a "
                                + "stack frame — if so, that frame is the fault, not the caller.",
                        clock.instant(),
                        Finding.evidenceOf("exception", e.getKey(),
                                "occurrences", String.valueOf(e.getValue()),
                                "source", logSource.toString()))));

        return findings;
    }

    private static String simpleName(String fqcn) {
        int lastDot = fqcn.lastIndexOf('.');
        return lastDot >= 0 ? fqcn.substring(lastDot + 1) : fqcn;
    }

    private static String truncate(String line) {
        if (line == null) {
            return "";
        }
        return line.length() <= 220 ? line : line.substring(0, 220) + "…";
    }
}
