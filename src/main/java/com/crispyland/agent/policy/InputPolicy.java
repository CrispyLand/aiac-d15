package com.crispyland.agent.policy;

/** Validates and normalizes user input before it is ever sent to the model. Swappable. */
public interface InputPolicy {

    /**
     * @return the normalized input to send
     * @throws PolicyViolationException if the input must not be sent
     */
    String apply(String userInput);
}
