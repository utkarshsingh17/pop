package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;
import ai.utkarsh.pop.domain.port.in.GetInvestigationUseCase;
import ai.utkarsh.pop.domain.port.in.StartInvestigationUseCase;
import ai.utkarsh.pop.infrastructure.web.InvestigationDtos.InvestigationResponse;
import ai.utkarsh.pop.infrastructure.web.InvestigationDtos.InvestigationSummaryResponse;
import ai.utkarsh.pop.infrastructure.web.InvestigationDtos.StartInvestigationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Driving adapter: HTTP entry point.
 *
 * <p>Thin by design — parse, delegate to one use case, map the result. No orchestration and no
 * business rules; those live in the application and domain layers.
 */
@RestController
@RequestMapping("/api/v1/investigations")
class InvestigationController {

    private final StartInvestigationUseCase startInvestigation;
    private final GetInvestigationUseCase getInvestigation;

    InvestigationController(StartInvestigationUseCase startInvestigation,
                            GetInvestigationUseCase getInvestigation) {
        this.startInvestigation = startInvestigation;
        this.getInvestigation = getInvestigation;
    }

    /**
     * Starts an investigation and blocks until the agent concludes.
     *
     * <p>Synchronous because an investigation is interactive — the caller wants the answer, not
     * a job id. Runs are bounded by the model's own limits and the per-tool timeouts.
     */
    @PostMapping
    ResponseEntity<InvestigationResponse> start(@Valid @RequestBody StartInvestigationRequest request,
                                                UriComponentsBuilder uriBuilder) {
        Investigation investigation = startInvestigation.start(
                new StartInvestigationUseCase.StartInvestigationCommand(
                        request.question(), request.service(), request.lookback()));

        URI location = uriBuilder.path("/api/v1/investigations/{id}")
                .buildAndExpand(investigation.id().value())
                .toUri();

        return ResponseEntity.created(location).body(InvestigationResponse.from(investigation));
    }

    @GetMapping("/{id}")
    InvestigationResponse byId(@PathVariable UUID id) {
        return InvestigationResponse.from(getInvestigation.byId(new InvestigationId(id)));
    }

    @GetMapping
    List<InvestigationSummaryResponse> recent(
            @RequestParam(defaultValue = "20") @Positive @Max(100) int limit) {
        return getInvestigation.recent(limit).stream()
                .map(InvestigationSummaryResponse::from)
                .toList();
    }
}
