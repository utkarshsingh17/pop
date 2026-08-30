package ai.utkarsh.pop.domain.port.in;

import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;

import java.util.List;

/** Driving port: read back investigations. */
public interface GetInvestigationUseCase {

    /** @throws InvestigationNotFoundException when no such investigation exists */
    Investigation byId(InvestigationId id);

    List<Investigation> recent(int limit);

    class InvestigationNotFoundException extends RuntimeException {

        private final InvestigationId id;

        public InvestigationNotFoundException(InvestigationId id) {
            super("No investigation found with id " + id);
            this.id = id;
        }

        public InvestigationId id() {
            return id;
        }
    }
}
