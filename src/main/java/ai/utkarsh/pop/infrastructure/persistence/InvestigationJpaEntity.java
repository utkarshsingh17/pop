package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.InvestigationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistence shape of the {@code Investigation} aggregate.
 *
 * <p>The id is assigned by the domain rather than generated, so Spring Data cannot use a
 * null id to detect a new entity. The nullable wrapper {@code @Version} field is what makes
 * new-state detection work here — it is null until the first flush.
 */
@Entity
@Table(name = "investigations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class InvestigationJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "service_name", nullable = false, length = 128)
    private String serviceName;

    @Column(name = "range_from", nullable = false)
    private Instant rangeFrom;

    @Column(name = "range_to", nullable = false)
    private Instant rangeTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvestigationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "root_cause", columnDefinition = "text")
    private String rootCause;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Confidence confidence;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "investigation_remediation_steps",
            joinColumns = @JoinColumn(name = "investigation_id"))
    @OrderColumn(name = "step_order")
    @Column(name = "step", nullable = false, columnDefinition = "text")
    private List<String> remediationSteps = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "investigation_supporting_findings",
            joinColumns = @JoinColumn(name = "investigation_id"))
    @OrderColumn(name = "finding_order")
    @Column(name = "finding_title", nullable = false, columnDefinition = "text")
    private List<String> supportingFindings = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "investigation_id", nullable = false)
    @OrderColumn(name = "finding_order")
    private List<FindingJpaEntity> findings = new ArrayList<>();

    InvestigationJpaEntity(UUID id) {
        this.id = id;
    }

    void apply(String question, String serviceName, Instant rangeFrom, Instant rangeTo,
               InvestigationStatus status, Instant createdAt, Instant startedAt, Instant completedAt,
               String rootCause, Confidence confidence, String summary, String failureReason,
               List<String> remediationSteps, List<String> supportingFindings,
               List<FindingJpaEntity> findings) {
        this.question = question;
        this.serviceName = serviceName;
        this.rangeFrom = rangeFrom;
        this.rangeTo = rangeTo;
        this.status = status;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.rootCause = rootCause;
        this.confidence = confidence;
        this.summary = summary;
        this.failureReason = failureReason;

        // Replace contents rather than the list instance — Hibernate tracks the instance.
        this.remediationSteps.clear();
        this.remediationSteps.addAll(remediationSteps);
        this.supportingFindings.clear();
        this.supportingFindings.addAll(supportingFindings);
        this.findings.clear();
        this.findings.addAll(findings);
    }
}
