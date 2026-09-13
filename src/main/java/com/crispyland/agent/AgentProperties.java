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
        Context context,
        Compression compression) {

    /**
     * The starting point for every turn: the declared defaults, plus whether compression is on.
     * Compression is configured with its own mechanism under {@code agent.compression} but is
     * overridable per turn, so it has to arrive in the same merge as everything else.
     */
    public AgentConfig defaultConfig() {
        return defaults.toConfig().toBuilder()
                .compressHistory(compression != null && compression.enabled())
                .build();
    }

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

    /**
     * History compression: the third answer to a growing prompt, after the message window and
     * the token window — rewrite the old turns instead of dropping them.
     *
     * @param enabled            default for new turns; the page may override it per request
     * @param keepRecentMessages tail always sent verbatim, never summarized
     * @param compressEvery      minimum backlog beyond the tail before a summarization is worth
     *                           the call it costs
     * @param model              which model writes the notes; a cheap one is usually right
     * @param maxSummaryTokens   ceiling on the notes, and therefore on this part of every prompt.
     *                           On a reasoning model the hidden reasoning tokens are billed
     *                           against this same ceiling, so it has to cover both
     * @param reasoningEffort    {@code ""} omits the parameter; on gpt-oss, {@code low} leaves
     *                           most of {@code maxSummaryTokens} for the notes themselves
     */
    public record Compression(boolean enabled,
                              int keepRecentMessages,
                              int compressEvery,
                              String model,
                              int maxSummaryTokens,
                              String reasoningEffort) {
    }
}
