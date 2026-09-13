package com.crispyland.agent;

import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;

/**
 * Everything the caller gets back: the answer, what it cost, what it was predicted to cost,
 * the settings actually used (after defaults were merged in), and the conversation as it
 * now stands.
 *
 * @param budget the pre-flight estimate, kept alongside {@code usage} so the prediction can
 *               be held against the provider's ground truth on every single turn
 */
public record AgentResult(
        String answer,
        AgentConfig effectiveConfig,
        TokenUsage usage,
        TokenUsage cumulativeUsage,
        ContextBudget budget,
        String finishReason,
        long latencyMillis,
        Verdict verdict,
        List<Message> transcript) {

    public AgentResult {
        transcript = (transcript == null) ? List.of() : List.copyOf(transcript);
    }

    /** How far the local estimate missed the provider's {@code prompt_tokens} by, in tokens. */
    public long promptTokenDrift() {
        return budget.promptTokens() - usage.promptTokens();
    }

    public double promptTokenDriftPercent() {
        long actual = usage.promptTokens();
        return (actual == 0) ? 0d : 100d * promptTokenDrift() / actual;
    }

    /**
     * The reply hit {@code max_completion_tokens} and was cut off mid-sentence. The call
     * succeeded, the answer is incomplete, and nothing in the response says so except this.
     */
    public boolean truncated() {
        return "length".equals(finishReason);
    }
}
