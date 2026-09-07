package com.crispyland.agent.llm;

import com.crispyland.agent.usage.TokenUsage;

/** Provider-neutral parsed reply. The agent never sees raw JSON. */
public record ChatResponse(String content, String model, String finishReason, TokenUsage usage) {

    public ChatResponse {
        usage = (usage == null) ? TokenUsage.NONE : usage;
    }
}
