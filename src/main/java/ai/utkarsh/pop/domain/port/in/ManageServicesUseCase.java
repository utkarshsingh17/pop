package ai.utkarsh.pop.domain.port.in;

import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;

import java.util.List;

/** Driving port: register and manage the services pop is allowed to investigate. */
public interface ManageServicesUseCase {

    MonitoredService register(RegisterServiceCommand command);

    /** Replaces the registration's mutable fields. Absent fields are left as they were. */
    MonitoredService update(ServiceName name, UpdateServiceCommand command);

    /** Redacted — never carries a password. */
    MonitoredService byName(ServiceName name);

    /** Redacted — never carries a password. */
    List<MonitoredService> all();

    void deregister(ServiceName name);

    /**
     * Opens a connection to the registered database and runs a trivial statement, so an
     * operator finds out the credentials are wrong at registration time rather than in the
     * middle of an incident.
     */
    ProbeResult probe(ServiceName name);

    record RegisterServiceCommand(String name,
                                  String prometheusLabel,
                                  String jdbcUrl,
                                  String username,
                                  String password) {
    }

    record UpdateServiceCommand(String prometheusLabel,
                                String jdbcUrl,
                                String username,
                                String password,
                                Boolean enabled) {
    }

    record ProbeResult(boolean reachable, String detail) {
    }

    class ServiceNotFoundException extends RuntimeException {
        public ServiceNotFoundException(ServiceName name) {
            super("No service is registered under the name '" + name + "'");
        }
    }

    class ServiceAlreadyRegisteredException extends RuntimeException {
        public ServiceAlreadyRegisteredException(ServiceName name) {
            super("A service is already registered under the name '" + name + "'");
        }
    }
}
