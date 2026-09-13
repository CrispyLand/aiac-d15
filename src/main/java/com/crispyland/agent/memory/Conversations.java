package com.crispyland.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Retention shared by every {@link ConversationStore}. The window rule belongs here rather
 * than in each store so that an in-memory dialogue and a persisted one can never disagree
 * about what the agent is allowed to remember.
 */
final class Conversations {

    private Conversations() {
    }

    /**
     * Returns {@code existing} plus {@code incoming}, oldest messages dropped until the stack
     * fits the window. Trimming is destructive: what falls out of the window is gone, from the
     * prompt and from the rendered transcript alike.
     *
     * @param maxMessages rolling window size; {@code <= 0} keeps everything
     */
    static List<Message> appendTrimmed(List<Message> existing, List<Message> incoming, int maxMessages) {
        List<Message> updated = new ArrayList<>(existing == null ? List.of() : existing);
        updated.addAll(incoming);

        int excess = (maxMessages > 0) ? updated.size() - maxMessages : 0;
        return excess > 0 ? new ArrayList<>(updated.subList(excess, updated.size())) : updated;
    }

    /**
     * Returns the {@code count} oldest messages — the half of a fork that is kept. A cut that
     * would leave a user message without its reply is moved back one, because a dangling user
     * turn reads to the model as a question it already refused to answer.
     *
     * @param count {@code < 0} or beyond the end means the whole stack
     */
    static List<Message> head(List<Message> existing, int count) {
        if (existing == null) {
            return new ArrayList<>();
        }
        if (count < 0 || count >= existing.size()) {
            return new ArrayList<>(existing);
        }
        int cut = (count > 0 && existing.get(count - 1).isUser()) ? count - 1 : count;
        return new ArrayList<>(existing.subList(0, cut));
    }

    /** Removes the {@code count} oldest messages, once a summary has taken their place. */
    static List<Message> drop(List<Message> existing, int count) {
        if (existing == null || count <= 0) {
            return (existing == null) ? new ArrayList<>() : existing;
        }
        return count >= existing.size()
                ? new ArrayList<>()
                : new ArrayList<>(existing.subList(count, existing.size()));
    }
}
