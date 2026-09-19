package com.crispyland.agent.memory;

import com.crispyland.agent.task.TaskState;
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
    private final Map<String, Facts> facts = new ConcurrentHashMap<>();
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
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
    public Facts facts(String conversationId) {
        return facts.getOrDefault(conversationId, Facts.EMPTY);
    }

    @Override
    public void saveFacts(String conversationId, Facts updated) {
        facts.put(conversationId, updated);
    }

    @Override
    public TaskState task(String conversationId) {
        return tasks.getOrDefault(conversationId, TaskState.EMPTY);
    }

    @Override
    public void saveTask(String conversationId, TaskState updated) {
        tasks.put(conversationId, updated);
    }

    @Override
    public int copy(String fromConversationId, String toConversationId, int messages) {
        List<Message> source = history(fromConversationId);
        List<Message> copied = Conversations.head(source, messages);
        boolean whole = copied.size() == source.size();
        conversations.put(toConversationId, copied);
        summaries.put(toConversationId, summary(fromConversationId));
        facts.put(toConversationId, whole ? facts(fromConversationId) : Facts.EMPTY);
        // Same rule as the facts, and for the same reason: a stage is not message-addressable, so
        // a fork taken four messages back cannot be rewound to the stage the task was in then. A
        // branch that starts in `validation` having validated nothing is worse than one that
        // starts with no task at all.
        tasks.put(toConversationId, whole ? task(fromConversationId) : TaskState.EMPTY);
        return copied.size();
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
        summaries.remove(conversationId);
        facts.remove(conversationId);
        tasks.remove(conversationId);
    }
}
