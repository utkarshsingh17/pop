package ai.utkarsh.pop.infrastructure.persistence;

import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Postgres container for persistence tests.
 *
 * <p>The container is started once per JVM and reused across test classes — a fresh
 * container per class would dominate the build time. H2 is deliberately not used: the schema
 * is Postgres-specific (timestamptz, UUID, ON DELETE CASCADE) and an in-memory substitute
 * would let real mapping drift pass.
 */
@Import(AbstractPostgresIntegrationTest.PostgresContainerConfig.class)
abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("pop")
            .withUsername("pop")
            .withPassword("pop");

    static {
        POSTGRES.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfig {

        @Bean
        DynamicPropertyRegistrar postgresProperties() {
            return registry -> {
                registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
                registry.add("spring.datasource.username", POSTGRES::getUsername);
                registry.add("spring.datasource.password", POSTGRES::getPassword);
                registry.add("spring.flyway.enabled", () -> true);
                registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            };
        }
    }
}
