package com.crispyland.agent.memory;

import java.util.List;

/**
 * Where the message stack lives. Swappable: the default keeps it in memory, but a Redis or
 * JDBC implementation would let the agent run as several stateless microservice instances.
 */
public interface ConversationStore {

    /** Prior messages, oldest first. Never null; empty for an unknown conversation. */
    List<Message> history(String conversationId);

    /** Appends messages to the stack, applying whatever retention policy the store has. */
    void append(String conversationId, List<Message> messages);

    void clear(String conversationId);
}
