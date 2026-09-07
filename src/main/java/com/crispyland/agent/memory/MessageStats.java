package com.crispyland.agent.memory;

/**
 * What one message cost, captured when it was recorded.
 * <p>
 * The provider reports usage per turn, not per message, so the turn's numbers are split
 * the way they were actually earned: the user message carries the prompt tokens (the whole
 * context replayed to produce it), the assistant message carries the completion tokens plus
 * the turn total, latency and finish reason.
 */
public record MessageStats(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long latencyMillis,
        String model,
        String finishReason) {

    public static MessageStats forPrompt(long promptTokens, String model) {
        return new MessageStats(promptTokens, 0, 0, 0, model, "");
    }

    public static MessageStats forCompletion(long completionTokens, long totalTokens,
                                             long latencyMillis, String model, String finishReason) {
        return new MessageStats(0, completionTokens, totalTokens, latencyMillis, model, finishReason);
    }
}
