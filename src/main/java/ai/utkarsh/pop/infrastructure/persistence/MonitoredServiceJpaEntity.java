package ai.utkarsh.pop.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistence shape of {@code MonitoredService}.
 *
 * <p>{@code dbPassword} holds ciphertext. The adapter encrypts on the way in and decrypts on the
 * way out, so neither this class nor the database ever sees the plaintext.
 *
 * <p>Like the investigation entity, the id is domain-assigned (the service name), so the
 * nullable {@code @Version} is what lets Spring Data tell an insert from an update.
 */
@Entity
@Table(name = "monitored_services")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class MonitoredServiceJpaEntity {

    @Id
    @Column(nullable = false, updatable = false, length = 128)
    private String name;

    @Version
    private Long version;

    @Column(name = "prometheus_label", length = 128)
    private String prometheusLabel;

    @Column(name = "db_url", length = 512)
    private String dbUrl;

    @Column(name = "db_username", length = 128)
    private String dbUsername;

    /** AES-GCM ciphertext, base64. Never plaintext. */
    @Column(name = "db_password", columnDefinition = "text")
    private String dbPassword;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    MonitoredServiceJpaEntity(String name) {
        this.name = name;
    }
}
