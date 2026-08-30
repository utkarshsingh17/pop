package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * HTTP shapes for the service registry.
 *
 * <p>There is no password field on any response type. That is the point: a secret this platform
 * accepted is never handed back out, not even to the caller who supplied it.
 */
final class MonitoredServiceDtos {

    private MonitoredServiceDtos() {
    }

    /**
     * {@code name} is optional when {@code url} is given — {@code {"url":"http://localhost:3001"}}
     * is a complete registration, and the name is derived from the host and port.
     */
    record RegisterServiceRequest(
            @Size(max = 128) String name,
            @Size(max = 512) String url,
            @Size(max = 128) String prometheusLabel,
            @Size(max = 512) String jdbcUrl,
            @Size(max = 128) String username,
            @Size(max = 256) String password) {
    }

    /** Every field optional — absent means "leave as it is". */
    record UpdateServiceRequest(
            @Size(max = 512) String url,
            @Size(max = 128) String prometheusLabel,
            @Size(max = 512) String jdbcUrl,
            @Size(max = 128) String username,
            @Size(max = 256) String password,
            Boolean enabled) {
    }

    record DatabaseResponse(String jdbcUrl, String username) {

        static DatabaseResponse from(DatabaseTarget target) {
            return new DatabaseResponse(target.jdbcUrl(), target.username());
        }
    }

    record ServiceResponse(
            String name,
            String actuatorUrl,
            String prometheusLabel,
            DatabaseResponse database,
            boolean enabled,
            Instant registeredAt,
            Instant updatedAt) {

        static ServiceResponse from(MonitoredService service) {
            return new ServiceResponse(
                    service.name().value(),
                    service.actuator().map(a -> a.baseUrl()).orElse(null),
                    service.effectivePrometheusLabel(),
                    service.database().map(DatabaseResponse::from).orElse(null),
                    service.enabled(),
                    service.registeredAt(),
                    service.updatedAt());
        }
    }

    record ProbeResponse(boolean reachable, String detail) {
    }
}
