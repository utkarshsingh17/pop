package ai.utkarsh.pop.application.tool;

import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.model.ServiceName;
import ai.utkarsh.pop.domain.model.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestigationContextTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private final InvestigationContext context = new InvestigationContext();

    private static Investigation investigation(String question) {
        return Investigation.open(question, ServiceName.of("svc"),
                TimeRange.lastly(Duration.ofHours(1), NOW), NOW);
    }

    @Test
    void runWithin_shouldExposeTheBoundInvestigation() {
        Investigation investigation = investigation("q");

        String question = context.runWithin(investigation, () -> context.require().question());

        assertThat(question).isEqualTo("q");
    }

    @Test
    void runWithin_shouldClearTheBindingAfterwards() {
        context.runWithin(investigation("q"), () -> "done");

        assertThat(context.current()).isEmpty();
    }

    @Test
    void runWithin_shouldClearTheBindingEvenWhenTheActionThrows() {
        assertThatThrownBy(() -> context.runWithin(investigation("q"), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // A leaked binding would let the next investigation record onto this one.
        assertThat(context.current()).isEmpty();
    }

    @Test
    void runWithin_whenAlreadyBound_shouldRefuseToNest() {
        assertThatThrownBy(() -> context.runWithin(investigation("outer"),
                () -> context.runWithin(investigation("inner"), () -> "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
    }

    @Test
    void require_outsideAnInvestigation_shouldThrow() {
        assertThatThrownBy(context::require)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No investigation is bound");
    }

    @Test
    void binding_shouldBeIsolatedBetweenThreads() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            String outer = context.runWithin(investigation("outer"), () -> {
                Future<Boolean> otherThreadSawBinding =
                        executor.submit(() -> context.current().isPresent());
                try {
                    assertThat(otherThreadSawBinding.get()).isFalse();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return context.require().question();
            });

            assertThat(outer).isEqualTo("outer");
        }
    }
}
