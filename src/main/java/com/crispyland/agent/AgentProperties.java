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
        Compression compression,
        FactMemory facts,
        LongTerm longTerm,
        Invariants invariants,
        Personalization profiles) {

    /**
     * Where the standing rules live.
     *
     * Where the standing rules live, and what the guard over them costs.
     *
     * @param file            its own file, and the one you would hand to somebody who asked what
     *                        this assistant is not allowed to do. A constraint buried in the
     *                        conversation log is a constraint nobody can audit — and unlike the
     *                        other stores there is no cap here, because a ceiling on invariants
     *                        would silently drop a rule somebody is relying on
     * @param model           which model rules on the rules a term search cannot settle; blank
     *                        falls back to the main one
     * @param maxTokens       ceiling on the guard's reply. It answers in one short line, so this
     *                        only needs to cover the hidden reasoning tokens spent before it
     * @param reasoningEffort {@code ""} omits the parameter, required for qwen/compound
     */
    public record Invariants(String file, String model, int maxTokens, String reasoningEffort) {
    }

    /**
     * Where the hand-edited profile files live.
     *
     * @param directory outside {@code src/main/resources} on purpose: packaged into the jar these
     *                  would be read-only at exactly the moment somebody wants to change how the
     *                  agent talks to them
     */
    public record Personalization(String directory) {

        public java.nio.file.Path path() {
            return java.nio.file.Path.of((directory == null || directory.isBlank())
                    ? "./profiles" : directory);
        }
    }

    /** The starting point for every turn: the declared per-request defaults. */
    public AgentConfig defaultConfig() {
        return defaults.toConfig();
    }

    /**
     * How many messages stay verbatim in short-term memory. One number, not two: the buffer
     * bound and the fold trigger are the same boundary seen from either side, and when they were
     * separate knobs a message could fall out of the window without ever being folded.
     */
    public int keepRecentMessages() {
        return (compression == null) ? 0 : compression.keepRecentMessages();
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
     * What is kept about a visitor once every conversation they had is gone.
     *
     * @param file       path to the long-term JSON, used only when {@code memory.store} is
     *                   {@code json}. Its own file, not a section of the conversation file: the
     *                   whole claim of this layer is that it is not tied to a conversation's
     *                   lifetime, and sharing storage is how that claim quietly stops being true
     * @param maxEntries ceiling per visitor. Unlike the other layers this one has no natural end —
     *                   nothing resets it and nobody is watching it grow — so the cap is the only
     *                   thing standing between a returning visitor and a prompt that starts large
     */
    public record LongTerm(String file, int maxEntries) {
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

    /**
     * How short-term memory stays bounded: keep the newest {@code keepRecentMessages} verbatim
     * and rewrite everything that falls out of that tail into the summary.
     *
     * @param keepRecentMessages tail always sent verbatim, never summarized
     * @param compressEvery      minimum backlog beyond the tail before a summarization is worth
     *                           the call it costs. Messages past the tail but short of this are
     *                           still sent — the backlog is deferred, never silently dropped
     * @param model              which model writes the notes; a cheap one is usually right
     * @param maxSummaryTokens   ceiling on the notes, and therefore on this part of every prompt.
     *                           On a reasoning model the hidden reasoning tokens are billed
     *                           against this same ceiling, so it has to cover both
     * @param reasoningEffort    {@code ""} omits the parameter; on gpt-oss, {@code low} leaves
     *                           most of {@code maxSummaryTokens} for the notes themselves
     */
    public record Compression(int keepRecentMessages,
                              int compressEvery,
                              String model,
                              int maxSummaryTokens,
                              String reasoningEffort) {
    }
}
