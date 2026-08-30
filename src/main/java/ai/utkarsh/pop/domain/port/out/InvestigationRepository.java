package ai.utkarsh.pop.domain.port.out;

import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;

import java.util.List;
import java.util.Optional;

/** Driven port: storage for {@link Investigation} aggregates. */
public interface InvestigationRepository {

    Investigation save(Investigation investigation);

    Optional<Investigation> findById(InvestigationId id);

    /** Most recent first. {@code limit} is capped by the adapter. */
    List<Investigation> findRecent(int limit);
}
