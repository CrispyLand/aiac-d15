package com.crispyland.agent.policy;

/** Validates and normalizes the model's reply before it leaves the agent. Swappable. */
public interface OutputPolicy {

    /**
     * @return the normalized answer to return
     * @throws PolicyViolationException if the reply is unusable
     */
    String apply(String modelOutput);
}
