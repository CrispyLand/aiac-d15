package com.crispyland.agent.memory;

import java.util.List;

/**
 * Everything remembered about one conversation, in the three shapes it is remembered in.
 * <p>
 * These are maintained together and selected between per turn: the transcript is always kept,
 * the summary and the fact block are each maintained only while their strategy is the active
 * one. Passing them as a single value is what stops the planner's signature from growing a
 * positional parameter every time a new form of memory is added — there will be a fourth, and
 * it belongs in here rather than in another argument.
 *
 * @param summary notes standing in for messages already folded out of {@code history}
 * @param facts   key/value block of what the dialogue has settled
 * @param history the transcript still held verbatim, oldest first
 */
public record MemoryState(Summary summary, Facts facts, List<Message> history) {

    public static final MemoryState EMPTY = new MemoryState(Summary.EMPTY, Facts.EMPTY, List.of());

    public MemoryState {
        summary = (summary == null) ? Summary.EMPTY : summary;
        facts = (facts == null) ? Facts.EMPTY : facts;
        history = (history == null) ? List.of() : List.copyOf(history);
    }

    /** A dialogue with nothing rewritten yet — the shape most tests and fresh sessions are in. */
    public static MemoryState of(List<Message> history) {
        return new MemoryState(Summary.EMPTY, Facts.EMPTY, history);
    }

    public static MemoryState of(Summary summary, List<Message> history) {
        return new MemoryState(summary, Facts.EMPTY, history);
    }

    public static MemoryState of(Facts facts, List<Message> history) {
        return new MemoryState(Summary.EMPTY, facts, history);
    }
}
