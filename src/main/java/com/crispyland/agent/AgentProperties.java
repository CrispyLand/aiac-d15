package com.crispyland.agent;

import com.crispyland.agent.usage.OverflowPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        Memory memory,
        Context context) {

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

    /**
     * The second window: the dialogue bounded in tokens rather than in messages.
     *
     * @param windows        context window per model id; a model absent here uses {@code defaultWindow}
     * @param defaultWindow  fallback window for any model not listed
     * @param overflowPolicy what to do when the next call will not fit
     * @param warnAt         fraction of the window (0–1) at which the page starts warning
     */
    public record Context(Map<String, Integer> windows,
                          int defaultWindow,
                          OverflowPolicy overflowPolicy,
                          double warnAt) {
    }
}
