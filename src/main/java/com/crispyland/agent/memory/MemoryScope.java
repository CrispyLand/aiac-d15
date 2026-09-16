package com.crispyland.agent.memory;

/**
 * Who is asking, and which of their dialogues this is — the two keys the memory layers hang off.
 * <p>
 * They were one string until long-term memory arrived, and conflating them was survivable only
 * while nothing outlived a conversation. It no longer is. {@code conversation} is a branch key:
 * resetting throws it away, forking mints another, and both are ordinary operations. {@code
 * visitor} is the person, and is meant to survive all of that. Keyed on one string, either
 * "remember me across a reset" or "these two branches must not see each other's state" has to
 * give, and which one gives would be decided by whichever call site happened to be written last.
 * <p>
 * Passing them as one value rather than two parameters is not decoration: it makes the argument
 * order impossible to swap, and a swap here is not a crash, it is one visitor quietly reading
 * another's long-term memory.
 *
 * @param visitor      the anonymous principal from the cookie; long-term memory is keyed on this
 * @param conversation the active branch key; short-term and working memory are keyed on this
 */
public record MemoryScope(String visitor, String conversation) {

    private static final String FALLBACK = "default";

    public MemoryScope {
        visitor = blank(visitor) ? FALLBACK : visitor;
        conversation = blank(conversation) ? visitor : conversation;
    }

    /**
     * A visitor sitting on their trunk branch, where {@link Branch#key} is the bare visitor id.
     * The shape a fresh session is in, and the shape most tests want.
     */
    public static MemoryScope of(String visitor) {
        return new MemoryScope(visitor, visitor);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
