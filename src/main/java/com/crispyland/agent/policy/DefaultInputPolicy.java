package com.crispyland.agent.policy;

/** Trims, rejects blank input, and enforces a maximum length. */
public class DefaultInputPolicy implements InputPolicy {

    private final int maxLength;

    public DefaultInputPolicy(int maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public String apply(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            throw new PolicyViolationException("Your message is empty — type a question first.");
        }
        String normalized = userInput.strip();
        if (maxLength > 0 && normalized.length() > maxLength) {
            throw new PolicyViolationException(
                    "Your message is %d characters; the limit is %d.".formatted(normalized.length(), maxLength));
        }
        return normalized;
    }
}
