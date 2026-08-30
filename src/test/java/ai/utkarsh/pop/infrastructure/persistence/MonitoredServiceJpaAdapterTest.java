package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.ActuatorEndpoint;
import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.infrastructure.config.SecurityProperties;
import ai.utkarsh.pop.infrastructure.security.SecretCipher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the registry round-trips through a real Postgres and, critically, that the password
 * is not readable in the table.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MonitoredServiceJpaAdapter.class, MonitoredServiceJpaAdapterTest.CipherConfig.class})
class MonitoredServiceJpaAdapterTest extends AbstractPostgresIntegrationTest {

    private static final ServiceName NAME = ServiceName.of("order-service");
    private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

    @Autowired
    private MonitoredServiceJpaAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class CipherConfig {
        @Bean
        SecretCipher secretCipher() {
            String key = Base64.getEncoder()
                    .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
            return new SecretCipher(new SecurityProperties(key, List.of(), List.of()));
        }
    }

    private static MonitoredService withDatabase() {
        return MonitoredService.register(NAME, "orders",
                new DatabaseTarget("jdbc:postgresql://db:5432/shop", "pop_readonly", "hunter2"),
                new ActuatorEndpoint("http://localhost:3001"),
                null, NOW);
    }

    @Test
    void shouldRoundTripARegistration() {
        adapter.save(withDatabase());

        MonitoredService loaded = adapter.findByName(NAME).orElseThrow();

        assertThat(loaded.name()).isEqualTo(NAME);
        assertThat(loaded.effectivePrometheusLabel()).isEqualTo("orders");
        assertThat(loaded.database().orElseThrow().password()).isEqualTo("hunter2");
        assertThat(loaded.enabled()).isTrue();
    }

    @Test
    void shouldStoreThePasswordEncrypted() {
        adapter.save(withDatabase());
        // Read back with raw JDBC, so the assertion is about what is actually in the column
        // rather than what the adapter hands back. That needs the insert flushed first.
        entityManager.flush();

        String stored = jdbc.queryForObject(
                "SELECT db_password FROM monitored_services WHERE name = ?", String.class, NAME.value());

        assertThat(stored).isNotNull().isNotEqualTo("hunter2").doesNotContain("hunter2");
    }

    @Test
    void listingShouldNotCarryPasswords() {
        adapter.save(withDatabase());

        List<MonitoredService> all = adapter.findAllRedacted();

        assertThat(all).singleElement()
                .satisfies(s -> assertThat(s.database().orElseThrow().password()).isEmpty());
    }

    @Test
    void shouldUpdateInPlaceRatherThanDuplicate() {
        MonitoredService service = withDatabase();
        adapter.save(service);
        service.updateDatabase(
                new DatabaseTarget("jdbc:postgresql://db2:5432/shop", "pop_readonly", "other"),
                NOW.plusSeconds(60));
        adapter.save(service);

        assertThat(adapter.findAllRedacted()).hasSize(1);
        assertThat(adapter.findByName(NAME).orElseThrow().database().orElseThrow().jdbcUrl())
                .isEqualTo("jdbc:postgresql://db2:5432/shop");
    }

    @Test
    void editingWithoutThePasswordShouldPreserveTheStoredCiphertext() {
        // The bug this covers: PATCHing a URL used to re-encrypt the password, so an unrelated
        // edit failed outright once the key had changed.
        adapter.save(withDatabase());
        entityManager.flush();
        String before = jdbc.queryForObject(
                "SELECT db_password FROM monitored_services WHERE name = ?", String.class, NAME.value());

        MonitoredService editing = adapter.findByNameWithoutSecrets(NAME).orElseThrow();
        editing.updateActuator(new ActuatorEndpoint("http://elsewhere:9000"), NOW.plusSeconds(60));
        adapter.save(editing);
        entityManager.flush();

        String after = jdbc.queryForObject(
                "SELECT db_password FROM monitored_services WHERE name = ?", String.class, NAME.value());
        assertThat(after).isEqualTo(before);
        assertThat(adapter.findByName(NAME).orElseThrow().database().orElseThrow().password())
                .isEqualTo("hunter2");
    }

    @Test
    void findByNameWithoutSecretsShouldNotDecrypt() {
        adapter.save(withDatabase());

        assertThat(adapter.findByNameWithoutSecrets(NAME).orElseThrow()
                .database().orElseThrow().password()).isEmpty();
    }

    @Test
    void shouldDeleteAndReportWhetherAnythingWasThere() {
        adapter.save(withDatabase());

        assertThat(adapter.deleteByName(NAME)).isTrue();
        assertThat(adapter.deleteByName(NAME)).isFalse();
        assertThat(adapter.findByName(NAME)).isEmpty();
    }

    @Test
    void shouldPersistARegistrationWithNoDatabase() {
        adapter.save(MonitoredService.register(ServiceName.of("metrics-only"), null, null, null, null, NOW));

        MonitoredService loaded = adapter.findByName(ServiceName.of("metrics-only")).orElseThrow();

        assertThat(loaded.hasDatabase()).isFalse();
    }
}
