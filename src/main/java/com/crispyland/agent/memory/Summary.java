package com.crispyland.agent.memory;

/**
 * The compressed head of a conversation: everything older than the verbatim window, rewritten
 * as notes and stored in place of the messages it replaces.
 * <p>
 * Kept apart from the message stack on purpose. The stack is a transcript — append-only, and
 * the model is charged for all of it on every turn. The summary is a <em>rewrite</em>: one
 * bounded blob that stands in for an unbounded number of past turns, refreshed each time more
 * of the dialogue falls out of the window. Because it is rewritten rather than appended to, it
 * is the only part of the context that does not grow with the conversation.
 * <p>
 * The running totals are what make the "before and after" comparison possible after the fact:
 * {@code replacedTokens} is what the folded messages were still costing on every call, and
 * {@code buildTokens} is what was spent on the summarization calls themselves. A compression
 * scheme that ignores the second number is only measuring half of its own bill.
 *
 * @param revision        how many times the summary has been rewritten; 0 means never
 * @param coveredMessages how many messages have been folded into it in total
 * @param replacedTokens  what those messages used to add to every single prompt
 * @param buildTokens     total tokens spent asking the model to write the summaries
 */
public record Summary(String text, int revision, int coveredMessages,
                      long replacedTokens, long buildTokens) {

    public static final Summary EMPTY = new Summary("", 0, 0, 0L, 0L);

    public Summary {
        text = (text == null) ? "" : text.strip();
    }

    public boolean isPresent() {
        return !text.isEmpty();
    }

    /** Replaces the notes with a newer rewrite and folds one more compaction into the totals. */
    public Summary rewrittenAs(String newText, int foldedMessages, long foldedTokens, long costTokens) {
        return new Summary(newText, revision + 1, coveredMessages + foldedMessages,
                replacedTokens + foldedTokens, buildTokens + costTokens);
    }
}
