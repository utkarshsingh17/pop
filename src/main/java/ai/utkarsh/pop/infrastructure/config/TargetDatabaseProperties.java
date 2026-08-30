package ai.utkarsh.pop.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Connection settings for the database pop <em>diagnoses</em> — distinct from pop's own
 * datasource, which Flyway manages.
 *
 * @param statementTimeout ceiling applied to every statement pop runs against the target, so a
 *                         pathological query the agent chooses cannot pin a production backend
 * @param maxRows          cap on rows returned into the model's context
 * @param maxPools         how many registered services may hold an open pool at once. Pools are
 *                         opened lazily per service and the least recently used is closed when
 *                         this is exceeded, so a large registry cannot exhaust local resources.
 */
@Validated
@ConfigurationProperties(prefix = "pop.target-datasource")
public record TargetDatabaseProperties(
        @NotBlank String url,
        @NotBlank String username,
        String password,
        @DefaultValue("5s") Duration statementTimeout,
        @DefaultValue("200") @Positive int maxRows,
        @DefaultValue("10") @Positive int maxPools) {
}
