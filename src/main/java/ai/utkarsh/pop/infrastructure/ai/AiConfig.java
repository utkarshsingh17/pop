package ai.utkarsh.pop.infrastructure.ai;

import ai.utkarsh.pop.application.tool.OpsTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the agent's {@link ChatClient}.
 *
 * <p>Note the Spring AI 2.0 shapes: {@link MessageWindowChatMemory} rather than the removed
 * {@code InMemoryChatMemory}, and {@code MessageChatMemoryAdvisor.builder(...)} rather than a
 * constructor. Every call through this client must supply a conversation id — 2.0 removed the
 * default and throws without one.
 */
@Configuration(proxyBeanMethods = false)
class AiConfig {

    /**
     * An investigation is a bounded conversation: a handful of tool calls and a conclusion.
     * Twenty messages is ample, and capping it stops a pathological loop from growing the
     * context (and the bill) without limit.
     */
    @Bean
    ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    ChatClient investigationChatClient(ChatClient.Builder builder,
                                       ChatMemory chatMemory,
                                       OpsTools opsTools) {
        return builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor())
                .defaultTools(opsTools)
                .build();
    }
}
