package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Severity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence shape of a {@code Finding}. Deliberately not the domain record — the domain
 * stays free of JPA, and the mapping lives in {@link InvestigationJpaAdapter}.
 */
@Entity
@Table(name = "findings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class FindingJpaEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FindingSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String detail;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "finding_evidence", joinColumns = @JoinColumn(name = "finding_id"))
    @MapKeyColumn(name = "evidence_key", length = 128)
    @Column(name = "evidence_value", nullable = false, columnDefinition = "text")
    private Map<String, String> evidence = new LinkedHashMap<>();

    static FindingJpaEntity of(UUID id, FindingSource source, Severity severity, String title,
                              String detail, Instant observedAt, Map<String, String> evidence) {
        FindingJpaEntity entity = new FindingJpaEntity();
        entity.id = id;
        entity.source = source;
        entity.severity = severity;
        entity.title = title;
        entity.detail = detail;
        entity.observedAt = observedAt;
        entity.evidence = new LinkedHashMap<>(evidence);
        return entity;
    }
}
