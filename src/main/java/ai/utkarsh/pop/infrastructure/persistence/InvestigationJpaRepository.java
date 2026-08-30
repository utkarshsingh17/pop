package ai.utkarsh.pop.infrastructure.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository. Kept package-private — callers go through the port adapter. */
interface InvestigationJpaRepository extends JpaRepository<InvestigationJpaEntity, UUID> {

    List<InvestigationJpaEntity> findAllByOrderByCreatedAtDescIdDesc(Limit limit);
}
