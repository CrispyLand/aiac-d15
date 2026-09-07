package com.crispyland.agent.policy;

import com.crispyland.agent.AgentException;

/** Thrown when input or output fails its policy. Expected, user-fixable failure. */
public class PolicyViolationException extends AgentException {

    public PolicyViolationException(String message) {
        super(message);
    }
}
