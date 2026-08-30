package ai.utkarsh.pop.infrastructure.config;

import ai.utkarsh.pop.domain.model.ServiceName;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Narrow seam between "which database does this service use" and the machinery that answers it.
 *
 * <p>The investigators depend on this rather than on {@link TargetDataSourceRegistry} directly,
 * so they know nothing about pool lifecycles, eviction or the registry table — and a test can
 * supply a fixed template with a lambda instead of standing up the whole registry.
 */
@FunctionalInterface
public interface TargetDatabaseResolver {

    /** The read-only template to query this service's database with. */
    JdbcTemplate jdbcFor(ServiceName service);
}
