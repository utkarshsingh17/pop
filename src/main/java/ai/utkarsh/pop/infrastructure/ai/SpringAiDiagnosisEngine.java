package ai.utkarsh.pop.infrastructure.ai;

import ai.utkarsh.pop.application.tool.InvestigationContext;
import ai.utkarsh.pop.domain.model.Confidence;
import ai.utkarsh.pop.domain.model.Diagnosis;
import ai.utkarsh.pop.domain.model.Investigation;
import ai.utkarsh.pop.domain.port.out.DiagnosisEnginePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driven adapter: runs the agent loop with Claude via Spring AI and returns a structured
 * {@link Diagnosis}.
 *
 * <p>The model is not asked to summarise evidence handed to it — it is given tools and decides
 * what to look at. Findings accumulate on the aggregate as a side effect of the tool calls,
 * which is why the investigation is bound to the thread for the duration of the call.
 */
@Slf4j
@Component
class SpringAiDiagnosisEngine implements DiagnosisEnginePort {

    private final ChatClient chatClient;
    private final InvestigationContext context;
    private final Resource systemPrompt;

    SpringAiDiagnosisEngine(ChatClient investigationChatClient,
                            InvestigationContext context,
                            @Value("classpath:prompts/investigate-system.st") Resource systemPrompt) {
        this.chatClient = investigationChatClient;
        this.context = context;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public Diagnosis diagnose(Investigation investigation) {
        // Bound for the whole call so every tool invocation the model makes records its
        // findings onto this aggregate and no other.
        return context.runWithin(investigation, () -> {
            try {
                AgentDiagnosis result = chatClient.prompt()
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, investigation.id().toString()))
                        .system(s -> s.text(systemPrompt)
                                .param("service", investigation.service().value())
                                .param("from", investigation.timeRange().from().toString())
                                .param("to", investigation.timeRange().to().toString()))
                        .user(investigation.question())
                        .call()
                        .entity(AgentDiagnosis.class);

                if (result == null) {
                    throw new DiagnosisFailedException("The model returned no diagnosis");
                }
                return result.toDomain();
            } catch (DiagnosisFailedException e) {
                throw e;
            } catch (RuntimeException e) {
                log.error("Agent loop failed for investigation {}", investigation.id(), e);
                throw new DiagnosisFailedException(
                        "The diagnosis engine failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * The shape Spring AI asks the model to fill in.
     *
     * <p>Separate from the domain {@link Diagnosis} on purpose: this is a wire contract shaped
     * for reliable model output (a plain string for confidence, so an unexpected value can be
     * coerced rather than blowing up deserialisation), and the domain record keeps its
     * invariants.
     */
    record AgentDiagnosis(
            String probableRootCause,
            String confidence,
            String summary,
            List<String> remediationSteps,
            List<String> supportingFindings) {

        Diagnosis toDomain() {
            if (probableRootCause == null || probableRootCause.isBlank()) {
                return Diagnosis.inconclusive(summary == null ? "No conclusion reached." : summary);
            }
            return new Diagnosis(probableRootCause, parseConfidence(confidence), summary,
                    remediationSteps, supportingFindings);
        }

        /** Models occasionally return "very high" or "medium-high"; degrade instead of failing. */
        private static Confidence parseConfidence(String raw) {
            if (raw == null) {
                return Confidence.LOW;
            }
            String normalised = raw.trim().toUpperCase(java.util.Locale.ROOT);
            for (Confidence candidate : Confidence.values()) {
                if (normalised.contains(candidate.name())) {
                    return candidate;
                }
            }
            return Confidence.LOW;
        }
    }
}
