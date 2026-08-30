package ai.utkarsh.pop.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * The conclusion drawn from an investigation's {@link Finding}s.
 *
 * <p>This is the shape the LLM is asked to produce via Spring AI structured output, so its
 * component names double as the field names the model fills in. Keep them descriptive.
 *
 * @param probableRootCause  the single most likely cause, stated plainly
 * @param confidence         how well the evidence actually supports that cause
 * @param summary            a short narrative an on-call engineer can read at 3am
 * @param remediationSteps   ordered, concrete actions — never vague advice like "investigate further"
 * @param supportingFindings titles of the findings this conclusion rests on
 */
public record Diagnosis(
        String probableRootCause,
        Confidence confidence,
        String summary,
        List<String> remediationSteps,
        List<String> supportingFindings) {

    public Diagnosis {
        if (probableRootCause == null || probableRootCause.isBlank()) {
            throw new IllegalArgumentException("probable root cause must not be blank");
        }
        Objects.requireNonNull(confidence, "confidence must not be null");
        summary = summary == null ? "" : summary;
        remediationSteps = remediationSteps == null ? List.of() : List.copyOf(remediationSteps);
        supportingFindings = supportingFindings == null ? List.of() : List.copyOf(supportingFindings);
    }

    /** Used when the agent gathered evidence but could not commit to a cause. */
    public static Diagnosis inconclusive(String summary) {
        return new Diagnosis("Inconclusive", Confidence.LOW, summary, List.of(), List.of());
    }
}
