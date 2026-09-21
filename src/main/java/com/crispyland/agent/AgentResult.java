package com.crispyland.agent;

import com.crispyland.agent.invariant.InvariantGuard;
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
 * @param compactedMessages how many older messages this turn folded into the summary before
 *                          it ran; 0 on every turn that did not trigger a compression
 * @param refusal           the standing rule this turn would have broken, when it would have. A
 *                          refusal comes back as an ordinary result rather than as an exception,
 *                          because unlike an overflow or a paused task, something useful was
 *                          produced: the rule, the reason for it, and what to do instead. The
 *                          {@code answer} <em>is</em> that redirect
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
        List<Message> transcript,
        int compactedMessages,
        InvariantGuard.Ruling refusal) {

    public AgentResult {
        transcript = (transcript == null) ? List.of() : List.copyOf(transcript);
        refusal = (refusal == null) ? InvariantGuard.Ruling.CLEAR : refusal;
    }

    /** True when the answer above is a refusal rather than a reply. */
    public boolean refused() {
        return refusal.breached();
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

    public boolean compacted() {
        return compactedMessages > 0;
    }
}
