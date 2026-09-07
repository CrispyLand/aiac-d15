package com.crispyland.agent.memory;

/**
 * One turn in a conversation. Domain type — no JSON, no provider wire format.
 * {@code stats} is null for messages that were never sent through the model
 * (the system prompt, or a message being built for the next request).
 */
public record Message(String role, String content, MessageStats stats) {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public static Message system(String content) {
        return new Message(SYSTEM, content, null);
    }

    public static Message user(String content) {
        return new Message(USER, content, null);
    }

    public static Message assistant(String content) {
        return new Message(ASSISTANT, content, null);
    }

    public Message withStats(MessageStats stats) {
        return new Message(role, content, stats);
    }

    public boolean isUser() {
        return USER.equals(role);
    }
}
