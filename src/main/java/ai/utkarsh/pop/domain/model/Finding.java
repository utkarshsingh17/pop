package ai.utkarsh.pop.domain.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One piece of evidence gathered by an investigator — a slow query, a saturated connection
 * pool, a latency percentile above its threshold.
 *
 * <p>Findings are facts, not conclusions. The LLM reads them to produce a {@link Diagnosis};
 * a Finding itself never contains speculation about root cause.
 *
 * @param evidence structured supporting data (query text, metric values, row counts) kept
 *                 as strings so it can be handed to the model and persisted without a
 *                 source-specific schema
 */
public record Finding(
        UUID id,
        FindingSource source,
        Severity severity,
        String title,
        String detail,
        Instant observedAt,
        Map<String, String> evidence) {

    public Finding {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("finding title must not be blank");
        }
        detail = detail == null ? "" : detail;
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public static Finding of(FindingSource source, Severity severity, String title, String detail,
                             Instant observedAt, Map<String, String> evidence) {
        return new Finding(UUID.randomUUID(), source, severity, title, detail, observedAt, evidence);
    }

    /** Convenience for adapters building evidence maps incrementally. */
    public static Map<String, String> evidenceOf(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("evidence requires an even number of key/value arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
