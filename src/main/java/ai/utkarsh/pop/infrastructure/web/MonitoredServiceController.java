package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.port.in.ManageServicesUseCase;
import ai.utkarsh.pop.infrastructure.web.MonitoredServiceDtos.ProbeResponse;
import ai.utkarsh.pop.infrastructure.web.MonitoredServiceDtos.RegisterServiceRequest;
import ai.utkarsh.pop.infrastructure.web.MonitoredServiceDtos.ServiceResponse;
import ai.utkarsh.pop.infrastructure.web.MonitoredServiceDtos.UpdateServiceRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Driving adapter for the service registry: what pop is allowed to investigate.
 *
 * <p>Thin like its sibling — parse, delegate, map. The interesting rules (host vetting,
 * credential encryption, pool lifecycle) live behind the use case.
 */
@RestController
@RequestMapping("/api/v1/services")
class MonitoredServiceController {

    private final ManageServicesUseCase manageServices;

    MonitoredServiceController(ManageServicesUseCase manageServices) {
        this.manageServices = manageServices;
    }

    @PostMapping
    ResponseEntity<ServiceResponse> register(@Valid @RequestBody RegisterServiceRequest request,
                                             UriComponentsBuilder uriBuilder) {
        var service = manageServices.register(new ManageServicesUseCase.RegisterServiceCommand(
                request.name(), request.prometheusLabel(),
                request.jdbcUrl(), request.username(), request.password()));

        URI location = uriBuilder.path("/api/v1/services/{name}")
                .buildAndExpand(service.name().value())
                .toUri();

        return ResponseEntity.created(location).body(ServiceResponse.from(service));
    }

    @GetMapping
    List<ServiceResponse> all() {
        return manageServices.all().stream().map(ServiceResponse::from).toList();
    }

    @GetMapping("/{name}")
    ServiceResponse byName(@PathVariable String name) {
        return ServiceResponse.from(manageServices.byName(ServiceName.of(name)));
    }

    @PatchMapping("/{name}")
    ServiceResponse update(@PathVariable String name,
                           @Valid @RequestBody UpdateServiceRequest request) {
        return ServiceResponse.from(manageServices.update(
                ServiceName.of(name),
                new ManageServicesUseCase.UpdateServiceCommand(
                        request.prometheusLabel(), request.jdbcUrl(),
                        request.username(), request.password(), request.enabled())));
    }

    @DeleteMapping("/{name}")
    ResponseEntity<Void> deregister(@PathVariable String name) {
        manageServices.deregister(ServiceName.of(name));
        return ResponseEntity.noContent().build();
    }

    /** Verify the registered credentials now, rather than discovering they are wrong mid-incident. */
    @PostMapping("/{name}/probe")
    ProbeResponse probe(@PathVariable String name) {
        var result = manageServices.probe(ServiceName.of(name));
        return new ProbeResponse(result.reachable(), result.detail());
    }
}
