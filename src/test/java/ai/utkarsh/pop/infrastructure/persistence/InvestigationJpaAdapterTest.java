package ai.utkarsh.pop.infrastructure.persistence;

import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Finding;
import ai.utkarsh.pop.domain.model.FindingSource;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.InvestigationId;
import ai.utkarsh.pop.domain.model.InvestigationStatus;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.Severity;
import ai.utkarsh.pop.domain.model.TimeRange;
import ai.utkarsh.pop.domain.port.out.InvestigationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the adapter against real Postgres. Uses the Flyway schema rather than
 * Hibernate DDL, so a drift between {@code V1__create_investigation_tables.sql} and the
 * entity mappings fails here instead of at runtime.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(InvestigationJpaAdapter.class)
class InvestigationJpaAdapterTest extends AbstractPostgresIntegrationTest {

    // Postgres stores timestamptz at microsecond precision; truncate so comparisons
    // don't fail on nanoseconds the database cannot keep.
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private InvestigationRepository repository;

    private static Investigation newInvestigation() {
        return Investigation.open("why is the order service slow?", ServiceName.of("order-service"),
                TimeRange.lastly(Duration.ofHours(1), NOW), NOW);
    }

    @Test
    void save_thenFindById_shouldRoundTripPendingInvestigation() {
        Investigation investigation = newInvestigation();

        repository.save(investigation);
        Investigation loaded = repository.findById(investigation.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(investigation.id());
        assertThat(loaded.question()).isEqualTo("why is the order service slow?");
        assertThat(loaded.service()).isEqualTo(ServiceName.of("order-service"));
        assertThat(loaded.status()).isEqualTo(InvestigationStatus.PENDING);
        assertThat(loaded.timeRange().from()).isEqualTo(investigation.timeRange().from());
        assertThat(loaded.findings()).isEmpty();
        assertThat(loaded.diagnosis()).isEmpty();
    }

    @Test
    void save_shouldRoundTripFindingsWithEvidenceInOrder() {
        Investigation investigation = newInvestigation();
        investigation.begin(NOW);
        investigation.recordFinding(Finding.of(FindingSource.POSTGRES, Severity.HIGH, "Slow query",
                "seq scan on orders", NOW, Map.of("calls", "1200", "mean_ms", "480.5")));
        investigation.recordFinding(Finding.of(FindingSource.PROMETHEUS, Severity.MEDIUM, "p99 latency",
                "above 2s", NOW, Map.of("value", "2.4")));

        repository.save(investigation);
        Investigation loaded = repository.findById(investigation.id()).orElseThrow();

        assertThat(loaded.findings()).hasSize(2);
        assertThat(loaded.findings().get(0).title()).isEqualTo("Slow query");
        assertThat(loaded.findings().get(0).evidence())
                .containsEntry("calls", "1200")
                .containsEntry("mean_ms", "480.5");
        assertThat(loaded.findings().get(1).source()).isEqualTo(FindingSource.PROMETHEUS);
        assertThat(loaded.highestSeverity()).isEqualTo(Severity.HIGH);
    }

    @Test
    void save_shouldRoundTripDiagnosisWithOrderedRemediationSteps() {
        Investigation investigation = newInvestigation();
        investigation.begin(NOW);
        investigation.recordFinding(Finding.of(FindingSource.POSTGRES, Severity.HIGH, "Slow query",
                "seq scan", NOW, Map.of()));
        investigation.concludeWith(new Diagnosis(
                "Missing index on orders.customer_id",
                Confidence.HIGH,
                "Sequential scan over 400k rows dominates request latency.",
                List.of("CREATE INDEX CONCURRENTLY idx_orders_customer_id ON orders (customer_id);",
                        "ANALYZE orders;",
                        "Re-run the investigation to confirm."),
                List.of("Slow query")), NOW);

        repository.save(investigation);
        Investigation loaded = repository.findById(investigation.id()).orElseThrow();

        assertThat(loaded.status()).isEqualTo(InvestigationStatus.COMPLETED);
        Diagnosis diagnosis = loaded.diagnosis().orElseThrow();
        assertThat(diagnosis.probableRootCause()).isEqualTo("Missing index on orders.customer_id");
        assertThat(diagnosis.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(diagnosis.remediationSteps())
                .containsExactly(
                        "CREATE INDEX CONCURRENTLY idx_orders_customer_id ON orders (customer_id);",
                        "ANALYZE orders;",
                        "Re-run the investigation to confirm.");
        assertThat(diagnosis.supportingFindings()).containsExactly("Slow query");
    }

    @Test
    void save_shouldRoundTripFailure() {
        Investigation investigation = newInvestigation();
        investigation.begin(NOW);
        investigation.fail("prometheus unreachable", NOW);

        repository.save(investigation);
        Investigation loaded = repository.findById(investigation.id()).orElseThrow();

        assertThat(loaded.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(loaded.failureReason()).contains("prometheus unreachable");
    }

    @Test
    void save_shouldUpdateInPlaceRatherThanInsertDuplicate() {
        Investigation investigation = newInvestigation();
        repository.save(investigation);

        investigation.begin(NOW);
        investigation.recordFinding(Finding.of(FindingSource.POSTGRES, Severity.LOW, "t", "d", NOW, Map.of()));
        repository.save(investigation);

        Investigation loaded = repository.findById(investigation.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(InvestigationStatus.INVESTIGATING);
        assertThat(loaded.findings()).hasSize(1);
        assertThat(repository.findRecent(50)).hasSize(1);
    }

    @Test
    void findById_whenAbsent_shouldReturnEmpty() {
        assertThat(repository.findById(new InvestigationId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void findRecent_shouldReturnNewestFirstAndCapLimit() {
        Instant base = NOW.minus(Duration.ofHours(5));
        for (int i = 0; i < 5; i++) {
            repository.save(Investigation.open("q" + i, ServiceName.of("svc"),
                    TimeRange.lastly(Duration.ofHours(1), base.plusSeconds(i * 60L)),
                    base.plusSeconds(i * 60L)));
        }

        List<Investigation> recent = repository.findRecent(3);

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).question()).isEqualTo("q4");
        assertThat(recent.get(2).question()).isEqualTo("q2");
    }

    @Test
    void findRecent_shouldClampNonPositiveLimit() {
        repository.save(newInvestigation());

        assertThat(repository.findRecent(0)).hasSize(1);
        assertThat(repository.findRecent(-10)).hasSize(1);
    }
}
