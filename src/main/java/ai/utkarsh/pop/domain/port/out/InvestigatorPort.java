package ai.utkarsh.pop.domain.port.out;

import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.TimeRange;

import java.util.List;

/**
 * Driven port: a source of operational evidence.
 *
 * <p>This is the platform's extension seam. Postgres and Prometheus implement it today;
 * logs, Kubernetes and tracing would each be one more implementation, with no change to the
 * domain or the use cases. {@link Finding} is the common currency that makes that possible.
 *
 * <p>Implementations must be side-effect free with respect to the system they observe —
 * an investigator reads, it never remediates.
 */
public interface InvestigatorPort {

    /** Which source this investigator speaks for; also used to route findings. */
    FindingSource source();

    /**
     * Sweeps every check this investigator knows about and returns what looks abnormal.
     *
     * <p>Must not throw for an unreachable backend — return an empty list or a single
     * {@link Finding} describing the collection failure, so one dead data source cannot
     * abort an entire investigation.
     */
    List<Finding> investigate(ServiceName service, TimeRange range);
}
