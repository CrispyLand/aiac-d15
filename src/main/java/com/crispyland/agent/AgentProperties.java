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
        Strategy strategy,
        Compression compression,
        FactMemory facts) {

    /**
     * The starting point for every turn: the declared defaults, plus the context strategy the
     * page opens on. The strategy is overridable per request — that is the whole point of the
     * tabs — so it has to arrive in the same merge as everything else.
     */
    public AgentConfig defaultConfig() {
        return defaults.toConfig().toBuilder()
                .contextStrategy(initialStrategy())
                .build();
    }

    public ContextStrategy initialStrategy() {
        if (strategy == null || strategy.initial() == null) {
            return ContextStrategy.SLIDING_WINDOW;
        }
        return strategy.initial();
    }

    public int windowMessages() {
        return (strategy == null) ? 0 : strategy.windowMessages();
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
     * @param branchFile  path to the branch refs; a separate file because refs are rewritten on
     *                    every fork and switch, and the transcripts are not
     */
    public record Memory(int maxMessages, String store, String file, String branchFile) {

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
     * How the prompt is packed, and how much of the transcript the message-window strategies
     * are willing to send.
     *
     * @param initial        which tab the page opens on
     * @param windowMessages tail sent verbatim under {@code sliding-window} and {@code sticky-facts}.
     *                       Ignored by {@code summary}, which folds the transcript in the store
     *                       instead of cutting it at read time
     */
    public record Strategy(ContextStrategy initial, int windowMessages) {
    }

    /**
     * History compression: the third answer to a growing prompt, after the message window and
     * the token window — rewrite the old turns instead of dropping them.
     *
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
    /**
     * The key/value memory. Where compression pays once every N turns for a large rewrite, this
     * pays a small amount on <em>every</em> turn — so the two have very different cost curves
     * even when the block and the summary end up the same size.
     *
     * @param model           which model does the extraction; blank falls back to the main one
     * @param maxFacts        ceiling on the block, and therefore on this segment of every prompt.
     *                        It is the number that keeps the cost flat instead of creeping
     * @param maxTokens       ceiling on the extraction reply. On gpt-oss this is shared with the
     *                        hidden reasoning tokens, which are spent first
     * @param reasoningEffort {@code ""} omits the parameter, required for qwen/compound
     */
    public record FactMemory(String model,
                             int maxFacts,
                             int maxTokens,
                             String reasoningEffort) {
    }

    public record Compression(int keepRecentMessages,
                              int compressEvery,
                              String model,
                              int maxSummaryTokens,
                              String reasoningEffort) {
    }
}
