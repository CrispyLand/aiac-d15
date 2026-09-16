package com.crispyland.agent.memory;

import java.util.List;

/**
 * Where long-term memory lives. Keyed by <em>visitor</em>, not by conversation.
 * <p>
 * That key is the whole difference between this and {@link ConversationStore}. A conversation id
 * is a container for one dialogue and is thrown away when the chat is reset; a visitor id outlives
 * every conversation it ever opened, which is exactly what makes "I remember you" possible and
 * exactly what makes an unforgettable entry a problem. Hence {@link #forgetAll}: a store that can
 * only accumulate has no honest answer to someone who wants out.
 */
public interface LongTermStore {

    /** What is known about a visitor. Never null; {@link LongTermMemory#EMPTY} for a stranger. */
    LongTermMemory recall(String visitorId);

    /**
     * Upserts routed entries and returns the block as it now stands.
     * <p>
     * Returns rather than voids because the caller has to render what it just wrote — going back
     * to {@link #recall} for it would be a second read of state another request may have moved.
     */
    LongTermMemory remember(String visitorId, List<LongTermMemory.Entry> entries);

    /** Drops one entry by {@link LongTermMemory.Entry#id()}. Unknown ids are not an error. */
    LongTermMemory forget(String visitorId, String entryId);

    /** Drops everything held about a visitor, leaving no key behind to be found again. */
    void forgetAll(String visitorId);
}
