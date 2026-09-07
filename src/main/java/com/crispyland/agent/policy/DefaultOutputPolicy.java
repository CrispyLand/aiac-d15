package com.crispyland.agent.policy;

/** Trims the reply, rejects an empty one, and optionally truncates it. */
public class DefaultOutputPolicy implements OutputPolicy {

    private final int maxLength;

    public DefaultOutputPolicy(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public String apply(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new PolicyViolationException(
                    "The model returned an empty answer — try raising max completion tokens.");
        }
        String normalized = modelOutput.strip();
        if (maxLength > 0 && normalized.length() > maxLength) {
            return normalized.substring(0, maxLength).stripTrailing() + "…";
        }
        return normalized;
    }
}
