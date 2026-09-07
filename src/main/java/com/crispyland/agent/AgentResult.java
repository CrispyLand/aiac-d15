package com.crispyland.agent;

import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.usage.TokenUsage;

/**
 * Everything the caller gets back: the answer, what it cost, and what was actually used
 * to produce it (the effective config, after defaults were merged in).
 */
public record AgentResult(
        String answer,
        AgentConfig effectiveConfig,
        TokenUsage usage,
        TokenUsage cumulativeUsage,
        String finishReason,
        long latencyMillis,
        Verdict verdict) {
}
