package ai.utkarsh.pop.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Investigation behaviour knobs.
 *
 * @param allowExplainAnalyze {@code EXPLAIN ANALYZE} actually executes the statement being
 *                            explained. Left off so the agent cannot run an expensive or
 *                            side-effecting query against a live system by choosing to
 *                            "measure" it.
 */
@ConfigurationProperties(prefix = "pop.investigation")
public record InvestigationProperties(
        @DefaultValue("1h") Duration defaultLookback,
        @DefaultValue("false") boolean allowExplainAnalyze) {
}
