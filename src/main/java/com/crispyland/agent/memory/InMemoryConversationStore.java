package com.crispyland.agent.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default store: a per-conversation message stack held in memory, trimmed to a rolling
 * window so a long dialogue cannot grow the prompt without bound.
 */
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Summary> summaries = new ConcurrentHashMap<>();
    private final int maxMessages;

    /** @param maxMessages rolling window size; {@code <= 0} keeps everything */
    public InMemoryConversationStore(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public List<Message> history(String conversationId) {
        return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        conversations.compute(conversationId,
                (key, existing) -> Conversations.appendTrimmed(existing, messages, maxMessages));
    }

    @Override
    public Summary summary(String conversationId) {
        return summaries.getOrDefault(conversationId, Summary.EMPTY);
    }

    @Override
    public void compact(String conversationId, Summary summary, int foldedMessages) {
        conversations.computeIfPresent(conversationId,
                (key, existing) -> Conversations.drop(existing, foldedMessages));
        summaries.put(conversationId, summary);
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
        summaries.remove(conversationId);
    }
}
