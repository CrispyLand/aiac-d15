package com.crispyland.agent.usage;

/** Token counts for a single call, mirroring the provider's {@code usage} object. */
public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                promptTokens + other.promptTokens(),
                completionTokens + other.completionTokens(),
                totalTokens + other.totalTokens());
    }
}
