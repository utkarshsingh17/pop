package ai.utkarsh.pop.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A service pop has been told to watch.
 *
 * <p>Registration is what turns {@link ServiceName} from a free-text label into something the
 * platform can resolve. Before this existed, the database under observation came from static
 * configuration and the service name on an investigation was decorative on the Postgres side.
 *
 * <p>Each optional field enables one investigator:
 * <ul>
 *   <li>{@code database} present → the Postgres investigator sweeps <em>that</em> database</li>
 *   <li>{@code prometheusLabel} → the value matched against the {@code service} label in PromQL</li>
 * </ul>
 *
 * <p>Pure Java, like the rest of the domain: no Spring, no JPA, and no knowledge of how the
 * password is protected at rest.
 */
public final class MonitoredService {

    private final ServiceName name;
    private final Instant registeredAt;

    private String prometheusLabel;
    private DatabaseTarget database;
    private boolean enabled;
    private Instant updatedAt;

    private MonitoredService(ServiceName name, String prometheusLabel, DatabaseTarget database,
                             boolean enabled, Instant registeredAt, Instant updatedAt) {
        this.name = name;
        this.prometheusLabel = prometheusLabel;
        this.database = database;
        this.enabled = enabled;
        this.registeredAt = registeredAt;
        this.updatedAt = updatedAt;
    }

    /** Registers a new service. {@code prometheusLabel} and {@code database} may both be null. */
    public static MonitoredService register(ServiceName name, String prometheusLabel,
                                            DatabaseTarget database, Instant now) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new MonitoredService(name, normaliseLabel(prometheusLabel), database, true, now, now);
    }

    /** Rehydrates from storage. Only persistence adapters should call this. */
    public static MonitoredService rehydrate(ServiceName name, String prometheusLabel,
                                             DatabaseTarget database, boolean enabled,
                                             Instant registeredAt, Instant updatedAt) {
        return new MonitoredService(name, prometheusLabel, database, enabled, registeredAt, updatedAt);
    }

    public void updateDatabase(DatabaseTarget target, Instant now) {
        this.database = target;
        this.updatedAt = now;
    }

    public void updatePrometheusLabel(String label, Instant now) {
        this.prometheusLabel = normaliseLabel(label);
        this.updatedAt = now;
    }

    public void disable(Instant now) {
        this.enabled = false;
        this.updatedAt = now;
    }

    public void enable(Instant now) {
        this.enabled = true;
        this.updatedAt = now;
    }

    /**
     * The label to match in PromQL. Falls back to the service name, which is what the demo
     * scrape config uses and what most deployments end up doing anyway.
     */
    public String effectivePrometheusLabel() {
        return prometheusLabel == null ? name.value() : prometheusLabel;
    }

    public boolean hasDatabase() {
        return database != null;
    }

    public ServiceName name() {
        return name;
    }

    public Optional<String> prometheusLabel() {
        return Optional.ofNullable(prometheusLabel);
    }

    public Optional<DatabaseTarget> database() {
        return Optional.ofNullable(database);
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static String normaliseLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        // Same constraint as ServiceName: this reaches Prometheus as a label matcher, so an
        // unconstrained value would let a caller inject PromQL through the selector.
        if (!trimmed.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException(
                    "prometheus label may only contain letters, digits, '_', '.', ':' and '-': " + label);
        }
        return trimmed;
    }

    @Override
    public String toString() {
        return "MonitoredService[name=%s, prometheusLabel=%s, database=%s, enabled=%s]"
                .formatted(name, prometheusLabel, database, enabled);
    }
}
