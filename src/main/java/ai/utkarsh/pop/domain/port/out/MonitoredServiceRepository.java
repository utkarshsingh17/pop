package ai.utkarsh.pop.domain.port.out;

import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;

import java.util.List;
import java.util.Optional;

/** Driven port: storage for the service registry. */
public interface MonitoredServiceRepository {

    MonitoredService save(MonitoredService service);

    /**
     * Returns the registration including its database password in plaintext, decrypting on the
     * way out. Callers must not log the result.
     */
    Optional<MonitoredService> findByName(ServiceName name);

    /**
     * The registration with its password left encrypted (the returned target carries an empty
     * one). For edits that do not touch the database: decrypting a secret nobody is going to use
     * is needless exposure, and it means an unrelated change cannot fail on a key that has since
     * been rotated.
     */
    Optional<MonitoredService> findByNameForEditing(ServiceName name);

    /** Registrations with passwords redacted — safe to render into an API response or a log. */
    List<MonitoredService> findAllRedacted();

    boolean deleteByName(ServiceName name);

    boolean existsByName(ServiceName name);
}
