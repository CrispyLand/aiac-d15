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
