package ai.utkarsh.pop.application.tool;

import ai.utkarsh.pop.domain.model.Investigation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Binds the investigation currently being worked on to the calling thread.
 *
 * <p>Tool methods are ordinary singleton beans — the model calls them by name and passes only
 * the arguments it chose, so there is no way for it to identify which investigation a call
 * belongs to. Rather than trusting the model to pass an investigation id (which it could get
 * wrong, or forge), the agent loop binds the aggregate here and the tools read it back.
 *
 * <p>A {@code ThreadLocal} is the right fit because one agent loop runs synchronously on one
 * thread. It is always cleared in a finally block; a leaked binding would let a later request
 * record findings onto someone else's investigation.
 */
@Component
public class InvestigationContext {

    private static final ThreadLocal<Investigation> CURRENT = new ThreadLocal<>();

    /** Runs {@code action} with {@code investigation} bound, clearing the binding afterwards. */
    public <T> T runWithin(Investigation investigation, Supplier<T> action) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("An investigation is already bound to this thread");
        }
        CURRENT.set(investigation);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    public Optional<Investigation> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** @throws IllegalStateException when called outside an agent loop */
    public Investigation require() {
        Investigation investigation = CURRENT.get();
        if (investigation == null) {
            throw new IllegalStateException(
                    "No investigation is bound to this thread; tools may only be called during an investigation");
        }
        return investigation;
    }
}
