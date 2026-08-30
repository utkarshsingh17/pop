package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.ActuatorEndpoint;
import ai.utkarsh.pop.domain.model.DatabaseTarget;
import ai.utkarsh.pop.domain.model.MonitoredService;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.port.out.MonitoredServiceRepository;
import ai.utkarsh.pop.infrastructure.security.SecretCipher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Driven adapter mapping {@code MonitoredService} to and from JPA, encrypting the database
 * password on the way in and decrypting it on the way out.
 *
 * <p>Putting the cipher here rather than in the domain is deliberate: how a secret is protected
 * at rest is a storage concern, and keeping it out of the aggregate lets the domain be tested
 * with no key configured.
 */
@Repository
class MonitoredServiceJpaAdapter implements MonitoredServiceRepository {

    private final MonitoredServiceJpaRepository repository;
    private final SecretCipher cipher;

    MonitoredServiceJpaAdapter(MonitoredServiceJpaRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public MonitoredService save(MonitoredService service) {
        MonitoredServiceJpaEntity entity = repository.findById(service.name().value())
                .orElseGet(() -> new MonitoredServiceJpaEntity(service.name().value()));

        entity.setPrometheusLabel(service.prometheusLabel().orElse(null));
        entity.setActuatorBaseUrl(service.actuator().map(ActuatorEndpoint::baseUrl).orElse(null));
        entity.setEnabled(service.enabled());
        entity.setRegisteredAt(service.registeredAt());
        entity.setUpdatedAt(service.updatedAt());

        service.database().ifPresentOrElse(target -> {
            entity.setDbUrl(target.jdbcUrl());
            entity.setDbUsername(target.username());
            // An empty password on an existing row means "unchanged" — the caller was editing
            // something else and never held the secret. Re-encrypting here would force every
            // unrelated edit through the cipher, so a rotated key would break editing a URL.
            boolean unchanged = target.password().isEmpty() && entity.getDbPassword() != null;
            if (!unchanged) {
                entity.setDbPassword(cipher.encrypt(target.password()));
            }
        }, () -> {
            entity.setDbUrl(null);
            entity.setDbUsername(null);
            entity.setDbPassword(null);
        });

        repository.save(entity);
        return service;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MonitoredService> findByName(ServiceName name) {
        return repository.findById(name.value()).map(entity -> toDomain(entity, true));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MonitoredService> findByNameForEditing(ServiceName name) {
        return repository.findById(name.value()).map(entity -> toDomain(entity, false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MonitoredService> findAllRedacted() {
        return repository.findAll().stream().map(entity -> toDomain(entity, false)).toList();
    }

    @Override
    @Transactional
    public boolean deleteByName(ServiceName name) {
        if (!repository.existsById(name.value())) {
            return false;
        }
        repository.deleteById(name.value());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByName(ServiceName name) {
        return repository.existsById(name.value());
    }

    /**
     * @param decryptSecret false for listing endpoints — decrypting a password nobody is going
     *                      to use is needless exposure, and it lets a listing still succeed
     *                      after a key rotation that a single lookup would fail on.
     */
    private MonitoredService toDomain(MonitoredServiceJpaEntity entity, boolean decryptSecret) {
        DatabaseTarget target = null;
        if (entity.getDbUrl() != null) {
            String password = decryptSecret ? cipher.decrypt(entity.getDbPassword()) : "";
            target = new DatabaseTarget(entity.getDbUrl(), entity.getDbUsername(), password);
        }
        return MonitoredService.rehydrate(
                ServiceName.of(entity.getName()),
                entity.getPrometheusLabel(),
                target,
                entity.getActuatorBaseUrl() == null ? null : new ActuatorEndpoint(entity.getActuatorBaseUrl()),
                entity.isEnabled(),
                entity.getRegisteredAt(),
                entity.getUpdatedAt());
    }
}
