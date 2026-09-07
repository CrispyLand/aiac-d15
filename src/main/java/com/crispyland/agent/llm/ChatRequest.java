package com.crispyland.agent.llm;

import com.crispyland.agent.memory.Message;
import java.util.List;

/**
 * Provider-neutral request the agent hands to an {@link LlmClient}.
 * No JSON, no HTTP — just what to ask and how.
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Integer maxCompletionTokens,
        String reasoningEffort,
        List<String> stopSequences,
        String responseSchema) {

    public ChatRequest {
        messages = List.copyOf(messages);
        stopSequences = (stopSequences == null) ? List.of() : List.copyOf(stopSequences);
    }
}
