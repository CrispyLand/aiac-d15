package com.crispyland.agent;

import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;

/**
 * Everything the caller gets back: the answer, what it cost, the settings actually used
 * (after defaults were merged in), and the conversation as it now stands.
 */
public record AgentResult(
        String answer,
        AgentConfig effectiveConfig,
        TokenUsage usage,
        TokenUsage cumulativeUsage,
        String finishReason,
        long latencyMillis,
        Verdict verdict,
        List<Message> transcript) {

    public AgentResult {
        transcript = (transcript == null) ? List.of() : List.copyOf(transcript);
    }
}
