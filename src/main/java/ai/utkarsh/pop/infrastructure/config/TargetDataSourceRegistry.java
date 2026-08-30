package ai.utkarsh.pop.infrastructure.config;

import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
import ai.utkarsh.pop.infrastructure.security.TargetUriGuard;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves which database a sweep should run against, given the service being investigated.
 *
 * <p>This replaces what used to be a single {@code targetDataSource} bean fixed at startup. A
 * registered service gets its own read-only pool, opened lazily on first use; anything not
 * registered falls back to the configured {@code pop.target-datasource.*}, which is what keeps
 * the bundled demo and the existing tests working unchanged.
 *
 * <p>Pools are held in a bounded LRU and closed on eviction. Without the bound, registering many
 * services would accumulate connection pools until the process ran out of file descriptors — and
 * each pool holds connections on someone else's production database, which is not a resource to
 * be casual with.
 *
 * <p>Every pool carries the same protections as the original single target: read-only at the pool
 * level, a per-connection {@code statement_timeout}, and a row cap on the template.
 */
@Slf4j
@Component
public class TargetDataSourceRegistry implements TargetDatabaseResolver {

    private final MonitoredServiceRepository services;
    private final TargetDatabaseProperties properties;
    private final TargetUriGuard guard;
    private final JdbcTemplate fallbackTemplate;

    /** Guarded by its own monitor; access-ordered so the eldest entry is the least recently used. */
    private final Map<String, Pooled> pools;

    TargetDataSourceRegistry(MonitoredServiceRepository services,
                             TargetDatabaseProperties properties,
                             TargetUriGuard guard,
                             @Qualifier(TargetDataSourceConfig.TARGET_JDBC_TEMPLATE) JdbcTemplate fallbackTemplate) {
        this.services = services;
        this.properties = properties;
        this.guard = guard;
        this.fallbackTemplate = fallbackTemplate;
        this.pools = new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * The template to sweep with for this service.
     *
     * <p>Falls back to the statically configured target when the service is not registered, has
     * no database, or is disabled — an unregistered name behaves exactly as it did before the
     * registry existed.
     */
    @Override
    public JdbcTemplate jdbcFor(ServiceName service) {
        return services.findByName(service)
                .filter(MonitoredService::enabled)
                .flatMap(MonitoredService::database)
                .map(target -> templateFor(service, target))
                .orElse(fallbackTemplate);
    }

    /** True when this service resolves to its own registered database rather than the fallback. */
    public boolean hasRegisteredDatabase(ServiceName service) {
        return services.findByName(service)
                .filter(MonitoredService::enabled)
                .map(MonitoredService::hasDatabase)
                .orElse(false);
    }

    /** Closes and forgets a service's pool, so the next sweep reconnects with fresh coordinates. */
    public void evict(ServiceName service) {
        synchronized (pools) {
            Pooled removed = pools.remove(service.value());
            if (removed != null) {
                close(service.value(), removed);
            }
        }
    }

    private JdbcTemplate templateFor(ServiceName service, DatabaseTarget target) {
        String fingerprint = fingerprint(target);
        synchronized (pools) {
            Pooled existing = pools.get(service.value());
            // A re-registration with new coordinates must not keep serving the old pool.
            if (existing != null && existing.fingerprint.equals(fingerprint)) {
                return existing.template;
            }
            if (existing != null) {
                log.info("Connection details for '{}' changed; rebuilding its pool", service);
                close(service.value(), existing);
                pools.remove(service.value());
            }

            guard.requireAllowed(target.jdbcUrl());
            Pooled created = open(service, target, fingerprint);
            pools.put(service.value(), created);
            evictDownToLimit();
            return created.template;
        }
    }

    private Pooled open(ServiceName service, DatabaseTarget target, String fingerprint) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(target.jdbcUrl());
        dataSource.setUsername(target.username());
        dataSource.setPassword(target.password());
        dataSource.setPoolName("pop-target-" + service.value());
        // Same restraint as the statically configured target: an investigation must never be
        // able to exhaust connections on the system it is diagnosing.
        dataSource.setMaximumPoolSize(3);
        dataSource.setMinimumIdle(0);
        dataSource.setReadOnly(true);
        dataSource.setAutoCommit(true);
        dataSource.setConnectionTimeout(5_000);
        dataSource.setIdleTimeout(30_000);
        dataSource.addDataSourceProperty("options",
                "-c statement_timeout=" + properties.statementTimeout().toMillis());

        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setMaxRows(properties.maxRows());
        template.setQueryTimeout((int) properties.statementTimeout().toSeconds());

        log.info("Opened read-only pool for service '{}'", service);
        return new Pooled(dataSource, template, fingerprint);
    }

    private void evictDownToLimit() {
        Iterator<Map.Entry<String, Pooled>> it = pools.entrySet().iterator();
        while (pools.size() > properties.maxPools() && it.hasNext()) {
            Map.Entry<String, Pooled> eldest = it.next();
            it.remove();
            log.info("Evicting least recently used target pool '{}' (limit {})",
                    eldest.getKey(), properties.maxPools());
            close(eldest.getKey(), eldest.getValue());
        }
    }

    private static void close(String service, Pooled pooled) {
        try {
            pooled.dataSource.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close target pool for '{}'", service, e);
        }
    }

    /**
     * Changing any connection detail must invalidate the pool. The password is included via its
     * hash rather than its value so it cannot reach a heap dump through this map.
     */
    private static String fingerprint(DatabaseTarget target) {
        return target.jdbcUrl() + "|" + target.username() + "|" + target.password().hashCode();
    }

    @PreDestroy
    void closeAll() {
        synchronized (pools) {
            pools.forEach(TargetDataSourceRegistry::close);
            pools.clear();
        }
    }

    private record Pooled(HikariDataSource dataSource, JdbcTemplate template, String fingerprint) {
    }
}
