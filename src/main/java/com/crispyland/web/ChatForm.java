package com.crispyland.web;

import com.crispyland.agent.AgentConfig;
import com.crispyland.agent.ContextStrategy;
import java.util.Arrays;
import java.util.List;

/**
 * Exactly what the HTML form posts. Its only job is to become an {@link AgentConfig};
 * any field left empty stays null so the agent applies its configured default.
 * <p>
 * {@code contextStrategy} is carried as a String rather than the enum on purpose: an unknown
 * value has to fall back to the default, not fail the whole binding. A form field that can be
 * edited from a URL should never be able to turn a typo into a 400.
 */
public record ChatForm(
        String userInput,
        String model,
        String systemPrompt,
        Double temperature,
        Integer maxCompletionTokens,
        String reasoningEffort,
        String stopSequences,
        String responseSchema,
        String contextStrategy) {

    public AgentConfig toAgentConfig() {
        return AgentConfig.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .temperature(temperature)
                .maxCompletionTokens(maxCompletionTokens)
                .reasoningEffort(reasoningEffort)
                .stopSequences(parseStopSequences())
                .responseSchema(responseSchema)
                .contextStrategy(ContextStrategy.from(contextStrategy))
                .build();
    }

    /** Prefills the form from a config (defaults on GET, the effective config after an answer). */
    public static ChatForm of(String userInput, AgentConfig config) {
        return new ChatForm(
                userInput,
                config.model(),
                config.systemPrompt(),
                config.temperature(),
                config.maxCompletionTokens(),
                config.reasoningEffort(),
                String.join(", ", config.stopSequencesOrEmpty()),
                config.responseSchema(),
                config.contextStrategyOrDefault().id());
    }

    private List<String> parseStopSequences() {
        if (stopSequences == null || stopSequences.isBlank()) {
            return null;
        }
        return Arrays.stream(stopSequences.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
