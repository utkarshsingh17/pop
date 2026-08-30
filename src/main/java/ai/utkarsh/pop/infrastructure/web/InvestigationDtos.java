package ai.utkarsh.pop.infrastructure.web;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.Investigation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP-facing shapes. Domain aggregates never cross the wire — these records are the contract,
 * and they are free to change independently of the model.
 */
final class InvestigationDtos {

    private InvestigationDtos() {
    }

    record StartInvestigationRequest(
            @NotBlank @Size(max = 2000) String question,
            @NotBlank @Size(max = 128) String service,
            /* ISO-8601, e.g. "PT2H". Null falls back to the configured default. */
            Duration lookback) {
    }

    record FindingResponse(
            UUID id,
            String source,
            String severity,
            String title,
            String detail,
            Instant observedAt,
            Map<String, String> evidence) {

        static FindingResponse from(Finding finding) {
            return new FindingResponse(finding.id(), finding.source().name(), finding.severity().name(),
                    finding.title(), finding.detail(), finding.observedAt(), finding.evidence());
        }
    }

    record DiagnosisResponse(
            String probableRootCause,
            String confidence,
            String summary,
            List<String> remediationSteps,
            List<String> supportingFindings) {
    }

    record InvestigationResponse(
            UUID id,
            String question,
            String service,
            String status,
            String highestSeverity,
            Instant windowFrom,
            Instant windowTo,
            Instant createdAt,
            Instant completedAt,
            DiagnosisResponse diagnosis,
            String failureReason,
            List<FindingResponse> findings) {

        static InvestigationResponse from(Investigation investigation) {
            return new InvestigationResponse(
                    investigation.id().value(),
                    investigation.question(),
                    investigation.service().value(),
                    investigation.status().name(),
                    investigation.highestSeverity().name(),
                    investigation.timeRange().from(),
                    investigation.timeRange().to(),
                    investigation.createdAt(),
                    investigation.completedAt().orElse(null),
                    investigation.diagnosis()
                            .map(d -> new DiagnosisResponse(d.probableRootCause(), d.confidence().name(),
                                    d.summary(), d.remediationSteps(), d.supportingFindings()))
                            .orElse(null),
                    investigation.failureReason().orElse(null),
                    investigation.findings().stream().map(FindingResponse::from).toList());
        }
    }

    /** Trimmed shape for list endpoints — findings would dominate the payload. */
    record InvestigationSummaryResponse(
            UUID id,
            String question,
            String service,
            String status,
            String highestSeverity,
            Instant createdAt,
            String probableRootCause,
            int findingCount) {

        static InvestigationSummaryResponse from(Investigation investigation) {
            return new InvestigationSummaryResponse(
                    investigation.id().value(),
                    investigation.question(),
                    investigation.service().value(),
                    investigation.status().name(),
                    investigation.highestSeverity().name(),
                    investigation.createdAt(),
                    investigation.diagnosis().map(d -> d.probableRootCause()).orElse(null),
                    investigation.findings().size());
        }
    }
}
