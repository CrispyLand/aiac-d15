package com.crispyland.agent.memory;

import java.util.Locale;

/**
 * The three things worth keeping after the conversation that produced them has ended.
 * <p>
 * This is a sub-division of one layer, not a fourth layer: all three are visitor-scoped and all
 * three survive a reset, so they share a lifetime. What they do not share is <em>why</em> the
 * agent is allowed to act on them, and that is worth separating because it decides what a wrong
 * entry costs. A wrong {@link #PROFILE} entry makes the agent address the wrong person; a wrong
 * {@link #DECISION} makes it build the wrong thing and cite the user as the authority for it.
 * <p>
 * The names are also the tags the extractor is asked to emit, so routing is a lookup rather than
 * a judgement call — see {@code MemoryRouter}. Anything that does not match one of these names
 * is dropped rather than guessed at.
 */
public enum LongTermKind {

    /** Who the user is: name, role, language, how they like to be answered. */
    PROFILE("Profile", "Who you are"),

    /** What the user has told the agent about the world that outlasts the task. */
    KNOWLEDGE("Knowledge", "What you have told me"),

    /**
     * Settled decisions, promoted here only once the task that argued about them is closed.
     * Long-term memory is shared by every branch, so a decision written here while it is still
     * under negotiation leaks into the forks that exist precisely to disagree with it.
     */
    DECISION("Decisions", "What was agreed");

    private final String label;
    private final String tagline;

    LongTermKind(String label, String tagline) {
        this.label = label;
        this.tagline = tagline;
    }

    /** Stable id for the tag the extractor emits, for form fields and for CSS hooks. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public String tagline() {
        return tagline;
    }

    /**
     * The kind named by an extractor's tag, or {@code null} if it named nothing recognisable.
     * <p>
     * Null rather than a default, because a default is how a model's typo becomes a permanent
     * entry in the wrong bucket. The router logs the unknown tag and drops the line: refusing to
     * store something is recoverable, storing it under the wrong lifetime is not.
     */
    public static LongTermKind from(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.strip().toLowerCase(Locale.ROOT);
        for (LongTermKind kind : values()) {
            if (kind.id().equals(normalized)) {
                return kind;
            }
        }
        return null;
    }
}
