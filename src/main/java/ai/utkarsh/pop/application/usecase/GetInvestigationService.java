package ai.utkarsh.pop.application.usecase;

import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;
import ai.utkarsh.pop.domain.port.in.GetInvestigationUseCase;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class GetInvestigationService implements GetInvestigationUseCase {

    private final InvestigationRepository repository;

    GetInvestigationService(InvestigationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Investigation byId(InvestigationId id) {
        return repository.findById(id).orElseThrow(() -> new InvestigationNotFoundException(id));
    }

    @Override
    public List<Investigation> recent(int limit) {
        return repository.findRecent(limit);
    }
}
