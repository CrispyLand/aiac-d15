package com.crispyland.agent.llm;

/** Talks to a chat-completions provider. The only abstraction allowed to do I/O. */
public interface LlmClient {

    /**
     * @throws LlmException on any transport or protocol failure
     */
    ChatResponse complete(ChatRequest request);
}
