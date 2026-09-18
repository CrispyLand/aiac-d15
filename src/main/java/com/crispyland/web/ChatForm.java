package com.crispyland.web;

import com.crispyland.agent.AgentConfig;
import java.util.Arrays;
import java.util.List;

/**
 * Exactly what the HTML form posts. Its only job is to become an {@link AgentConfig};
 * any field left empty stays null so the agent applies its configured default.
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
        String lens) {

    /**
     * The lens picked for this turn, or null to let the message and the user's default decide.
     * <p>
     * Deliberately not part of {@link AgentConfig}: everything in there is a provider request
     * parameter, and a lens is not sent anywhere — it selects which text gets rendered and which
     * limits apply. Letting it ride along in the config would put a personalization concept
     * inside the one object whose job is to be provider-neutral.
     */
    public String selectedLens() {
        return (lens == null || lens.isBlank()) ? null : lens.strip();
    }

    public AgentConfig toAgentConfig() {
        return AgentConfig.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .temperature(temperature)
                .maxCompletionTokens(maxCompletionTokens)
                .reasoningEffort(reasoningEffort)
                .stopSequences(parseStopSequences())
                .responseSchema(responseSchema)
                .build();
    }

    /** Prefills the form from a config (defaults on GET, the effective config after an answer). */
    public static ChatForm of(String userInput, AgentConfig config) {
        return of(userInput, config, null);
    }

    public static ChatForm of(String userInput, AgentConfig config, String lens) {
        return new ChatForm(
                userInput,
                config.model(),
                config.systemPrompt(),
                config.temperature(),
                config.maxCompletionTokens(),
                config.reasoningEffort(),
                String.join(", ", config.stopSequencesOrEmpty()),
                config.responseSchema(),
                lens);
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
