package com.crispyland.agent;

/** Base failure type for everything the agent can go wrong at. Callers only need this one. */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
