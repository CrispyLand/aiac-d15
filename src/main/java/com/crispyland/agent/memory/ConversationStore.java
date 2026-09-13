package com.crispyland.agent.memory;

import java.util.List;

/**
 * Where the message stack lives. Swappable: the default keeps it in memory, but a Redis or
 * JDBC implementation would let the agent run as several stateless microservice instances.
 */
public interface ConversationStore {

    /** Prior messages, oldest first. Never null; empty for an unknown conversation. */
    List<Message> history(String conversationId);

    /**
     * The notes standing in for everything older than {@link #history}. Never null;
     * {@link Summary#EMPTY} until the dialogue has been compressed at least once.
     */
    Summary summary(String conversationId);

    /** Appends messages to the stack, applying whatever retention policy the store has. */
    void append(String conversationId, List<Message> messages);

    /**
     * Swaps the {@code foldedMessages} oldest messages for {@code summary}, in one step so the
     * conversation is never briefly missing both. The messages are gone afterwards: the point of
     * compression is that the summary is what remains of them, here and in the prompt alike.
     */
    void compact(String conversationId, Summary summary, int foldedMessages);

    void clear(String conversationId);
}
