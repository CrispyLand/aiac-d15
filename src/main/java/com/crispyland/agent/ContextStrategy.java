package com.crispyland.agent;

import java.util.Locale;

/**
 * How the next prompt is assembled out of a conversation that no longer fits in it.
 * <p>
 * Every strategy here answers the same question — <em>the dialogue is longer than what you can
 * afford to send, so what do you send?</em> — and they differ in what they are willing to lose:
 * <ul>
 *   <li>{@link #SLIDING_WINDOW} loses the past outright. Cheapest, zero extra calls, and the
 *       failure mode is amnesia: anything agreed twenty messages ago is simply gone.</li>
 *   <li>{@link #STICKY_FACTS} loses the <em>wording</em> of the past but keeps its conclusions,
 *       by maintaining a small key/value block alongside the window.</li>
 *   <li>{@link #SUMMARY} loses the detail but keeps the narrative, by rewriting the old turns
 *       into prose notes.</li>
 * </ul>
 * They are deliberately peers: all three are pure prompt-assembly policies over the same stored
 * transcript, which is what makes flipping between them a fair comparison rather than a rebuild.
 * Branching is <em>not</em> in this list on purpose — it changes which transcript you are in,
 * not how that transcript is packed, so it composes with all three instead of competing with them.
 */
public enum ContextStrategy {

    SLIDING_WINDOW("Sliding window",
            "The last N messages, nothing else. No extra calls, and no memory of anything older."),
    STICKY_FACTS("Sticky facts",
            "A key/value block of what has been decided, kept current after every message, "
                    + "sent ahead of the last N messages."),
    SUMMARY("Summary",
            "Older turns are rewritten into notes and the messages themselves are dropped, so "
                    + "the prompt stops growing without the dialogue losing what happened.");

    private final String label;
    private final String tagline;

    ContextStrategy(String label, String tagline) {
        this.label = label;
        this.tagline = tagline;
    }

    /** Stable id used in URLs and form fields — {@code sliding-window}, {@code sticky-facts}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String label() {
        return label;
    }

    public String tagline() {
        return tagline;
    }

    /**
     * Lenient parse of an id, an enum name, or anything in between.
     *
     * @return {@code null} for blank or unrecognised input, so the caller's own default applies
     *         rather than a stranger's query string picking the strategy
     */
    public static ContextStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (ContextStrategy strategy : values()) {
            if (strategy.name().equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }
}
