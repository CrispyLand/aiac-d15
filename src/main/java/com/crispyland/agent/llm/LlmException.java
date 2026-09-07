package com.crispyland.agent.llm;

import com.crispyland.agent.AgentException;

/** Transport-level failure: non-2xx from the provider, unreachable host, unparseable body. */
public class LlmException extends AgentException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
