package com.crispyland.agent.usage;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe running total of everything the agent has spent since startup. */
public class TokenUsageTracker {

    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();

    /** Records one call's usage and returns the new cumulative total. */
    public TokenUsage record(TokenUsage usage) {
        TokenUsage delta = (usage == null) ? TokenUsage.NONE : usage;
        promptTokens.addAndGet(delta.promptTokens());
        completionTokens.addAndGet(delta.completionTokens());
        totalTokens.addAndGet(delta.totalTokens());
        calls.incrementAndGet();
        return cumulative();
    }

    public TokenUsage cumulative() {
        return new TokenUsage(promptTokens.get(), completionTokens.get(), totalTokens.get());
    }

    public long calls() {
        return calls.get();
    }
}
