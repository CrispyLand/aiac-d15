package com.crispyland.agent;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable, bound from {@code agent.*} in application.yml.
 * Nothing about the request is hardcoded in Java.
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String apiKey,
        String endpoint,
        Duration connectTimeout,
        Duration readTimeout,
        List<String> availableModels,
        List<String> reasoningEfforts,
        Defaults defaults,
        Limit inputPolicy,
        Limit outputPolicy,
        Memory memory) {

    /** Per-request parameter defaults used whenever the caller does not supply a value. */
    public record Defaults(
            String model,
            String systemPrompt,
            Double temperature,
            Integer maxCompletionTokens,
            String reasoningEffort,
            List<String> stopSequences,
            String responseSchema) {

        public AgentConfig toConfig() {
            return AgentConfig.builder()
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .temperature(temperature)
                    .maxCompletionTokens(maxCompletionTokens)
                    .reasoningEffort(reasoningEffort)
                    .stopSequences(stopSequences == null ? List.of() : stopSequences)
                    .responseSchema(responseSchema)
                    .build();
        }
    }

    /** A character limit; {@code 0} means unlimited. */
    public record Limit(int maxLength) {
    }

    /**
     * Conversation retention and where the dialogue lives.
     *
     * @param maxMessages rolling window size; {@code <= 0} keeps the whole dialogue
     * @param store       {@code json} to survive restarts, {@code memory} to forget on shutdown
     * @param file        path to the JSON history, used only when {@code store} is {@code json}
     */
    public record Memory(int maxMessages, String store, String file) {

        public static final String JSON = "json";
    }
}
