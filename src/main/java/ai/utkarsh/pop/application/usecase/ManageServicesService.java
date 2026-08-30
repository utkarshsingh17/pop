package ai.utkarsh.pop.application.usecase;

import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
import ai.utkarsh.pop.infrastructure.config.TargetDataSourceRegistry;
import ai.utkarsh.pop.infrastructure.security.TargetUriGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * Manages the registry of services pop may investigate.
 *
 * <p>Registration is the security checkpoint: this is where a caller-supplied host is vetted,
 * before any pool is opened for it. Doing it here rather than at connect time means a rejected
 * target is never written to the registry in the first place.
 */
@Slf4j
@Service
public class ManageServicesService implements ManageServicesUseCase {

    private final MonitoredServiceRepository repository;
    private final TargetDataSourceRegistry registry;
    private final TargetUriGuard guard;
    private final Clock clock;

    ManageServicesService(MonitoredServiceRepository repository,
                          TargetDataSourceRegistry registry,
                          TargetUriGuard guard,
                          Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.guard = guard;
        this.clock = clock;
    }

    @Override
    public MonitoredService register(RegisterServiceCommand command) {
        ServiceName name = ServiceName.of(command.name());
        if (repository.existsByName(name)) {
            throw new ServiceAlreadyRegisteredException(name);
        }

        DatabaseTarget target = databaseTargetOf(
                command.jdbcUrl(), command.username(), command.password());

        MonitoredService service = MonitoredService.register(
                name, command.prometheusLabel(), target, clock.instant());

        repository.save(service);
        log.info("Registered service '{}' (database configured: {})", name, service.hasDatabase());
        return redact(service);
    }

    @Override
    public MonitoredService update(ServiceName name, UpdateServiceCommand command) {
        MonitoredService service = repository.findByName(name)
                .orElseThrow(() -> new ServiceNotFoundException(name));

        if (command.prometheusLabel() != null) {
            service.updatePrometheusLabel(command.prometheusLabel(), clock.instant());
        }
        if (command.jdbcUrl() != null) {
            service.updateDatabase(
                    databaseTargetOf(command.jdbcUrl(), command.username(), command.password()),
                    clock.instant());
        }
        if (command.enabled() != null) {
            if (command.enabled()) {
                service.enable(clock.instant());
            } else {
                service.disable(clock.instant());
            }
        }

        repository.save(service);
        // The old pool may point at coordinates that no longer apply.
        registry.evict(name);
        log.info("Updated service '{}'", name);
        return redact(service);
    }

    @Override
    public MonitoredService byName(ServiceName name) {
        return repository.findAllRedacted().stream()
                .filter(service -> service.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ServiceNotFoundException(name));
    }

    @Override
    public List<MonitoredService> all() {
        return repository.findAllRedacted();
    }

    @Override
    public void deregister(ServiceName name) {
        if (!repository.deleteByName(name)) {
            throw new ServiceNotFoundException(name);
        }
        registry.evict(name);
        log.info("Deregistered service '{}'", name);
    }

    /**
     * Connects and runs a trivial statement. Reports failure as a result rather than an
     * exception — "the credentials are wrong" is the answer to this question, not an error in
     * answering it.
     */
    @Override
    public ProbeResult probe(ServiceName name) {
        MonitoredService service = repository.findByName(name)
                .orElseThrow(() -> new ServiceNotFoundException(name));

        // Disabled and never-configured both fall back to the static target, but they are
        // different mistakes and the operator needs to know which one they made.
        if (!service.enabled()) {
            return new ProbeResult(false,
                    "Service '" + name + "' is disabled, so sweeps fall back to the statically "
                            + "configured target. Re-enable it to use its registered database.");
        }
        if (!service.hasDatabase()) {
            return new ProbeResult(false,
                    "No database is registered for '" + name + "', so sweeps fall back to "
                            + "the statically configured target.");
        }
        try {
            JdbcTemplate template = registry.jdbcFor(name);
            template.queryForObject("SELECT 1", Integer.class);
            return new ProbeResult(true, "Connected and ran a read-only statement successfully.");
        } catch (RuntimeException e) {
            log.warn("Probe failed for service '{}': {}", name, e.getMessage());
            return new ProbeResult(false, "Could not query the registered database: " + e.getMessage());
        }
    }

    /** Null URL means "no database registered"; a partial target is rejected by the domain. */
    private DatabaseTarget databaseTargetOf(String jdbcUrl, String username, String password) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        guard.requireAllowed(jdbcUrl);
        return new DatabaseTarget(jdbcUrl, username, password == null ? "" : password);
    }

    private static MonitoredService redact(MonitoredService service) {
        return MonitoredService.rehydrate(
                service.name(),
                service.prometheusLabel().orElse(null),
                service.database().map(DatabaseTarget::redacted).orElse(null),
                service.enabled(),
                service.registeredAt(),
                service.updatedAt());
    }
}
