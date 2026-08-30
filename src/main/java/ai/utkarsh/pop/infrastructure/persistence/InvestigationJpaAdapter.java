package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Driven adapter: implements the domain's {@link InvestigationRepository} port on top of JPA,
 * translating between the pure aggregate and {@link InvestigationJpaEntity}.
 *
 * <p>All JPA knowledge stops here. Nothing above this class sees an entity.
 */
@Repository
class InvestigationJpaAdapter implements InvestigationRepository {

    private static final int MAX_RECENT = 100;

    private final InvestigationJpaRepository repository;

    InvestigationJpaAdapter(InvestigationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Investigation save(Investigation investigation) {
        InvestigationJpaEntity entity = repository.findById(investigation.id().value())
                .orElseGet(() -> new InvestigationJpaEntity(investigation.id().value()));

        Diagnosis diagnosis = investigation.diagnosis().orElse(null);

        entity.apply(
                investigation.question(),
                investigation.service().value(),
                investigation.timeRange().from(),
                investigation.timeRange().to(),
                investigation.status(),
                investigation.createdAt(),
                investigation.startedAt().orElse(null),
                investigation.completedAt().orElse(null),
                diagnosis == null ? null : diagnosis.probableRootCause(),
                diagnosis == null ? null : diagnosis.confidence(),
                diagnosis == null ? null : diagnosis.summary(),
                investigation.failureReason().orElse(null),
                diagnosis == null ? List.of() : diagnosis.remediationSteps(),
                diagnosis == null ? List.of() : diagnosis.supportingFindings(),
                investigation.findings().stream().map(InvestigationJpaAdapter::toEntity).toList());

        repository.save(entity);
        return investigation;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Investigation> findById(InvestigationId id) {
        return repository.findById(id.value()).map(InvestigationJpaAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Investigation> findRecent(int limit) {
        int capped = Math.clamp(limit, 1, MAX_RECENT);
        return repository.findAllByOrderByCreatedAtDescIdDesc(Limit.of(capped)).stream()
                .map(InvestigationJpaAdapter::toDomain)
                .toList();
    }

    private static FindingJpaEntity toEntity(Finding finding) {
        return FindingJpaEntity.of(finding.id(), finding.source(), finding.severity(),
                finding.title(), finding.detail(), finding.observedAt(), finding.evidence());
    }

    private static Finding toDomain(FindingJpaEntity entity) {
        return new Finding(entity.getId(), entity.getSource(), entity.getSeverity(),
                entity.getTitle(), entity.getDetail(), entity.getObservedAt(), entity.getEvidence());
    }

    private static Investigation toDomain(InvestigationJpaEntity entity) {
        Diagnosis diagnosis = null;
        if (entity.getRootCause() != null) {
            diagnosis = new Diagnosis(
                    entity.getRootCause(),
                    entity.getConfidence() == null ? Confidence.LOW : entity.getConfidence(),
                    entity.getSummary(),
                    entity.getRemediationSteps(),
                    entity.getSupportingFindings());
        }

        return Investigation.rehydrate(
                new InvestigationId(entity.getId()),
                entity.getQuestion(),
                ServiceName.of(entity.getServiceName()),
                new TimeRange(entity.getRangeFrom(), entity.getRangeTo()),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                diagnosis,
                entity.getFailureReason(),
                entity.getFindings().stream().map(InvestigationJpaAdapter::toDomain).toList());
    }
}
