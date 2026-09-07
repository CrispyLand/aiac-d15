package com.crispyland.agent.memory;

/** One turn in a conversation. Domain type — no JSON, no provider wire format. */
public record Message(String role, String content) {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public static Message system(String content) {
        return new Message(SYSTEM, content);
    }

    public static Message user(String content) {
        return new Message(USER, content);
    }

    public static Message assistant(String content) {
        return new Message(ASSISTANT, content);
    }

    public boolean isUser() {
        return USER.equals(role);
    }
}
