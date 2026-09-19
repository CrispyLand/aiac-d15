package com.crispyland.agent.task;

import java.util.Locale;

/**
 * Whose move it is.
 * <p>
 * An expected action with nobody attached to it is just a sentence. The actor is what turns it
 * into something the page can render as <em>waiting on you</em> versus <em>waiting on me</em>, and
 * that indicator is the only visible payoff of tracking an expected action at all.
 * <p>
 * Worth being honest about {@link #AGENT}: this assistant cannot act between turns. There is no
 * loop, no tools, and nothing happens until a person sends a message. So "waiting on me" currently
 * means "the next message should be my answer to something already asked", not "work is underway
 * elsewhere". The distinction is real and the demo should say so rather than let the label imply
 * an autonomy that does not exist yet.
 */
public enum AwaitedFrom {

    /** The next move is the user's: an answer, a decision, a file, a go-ahead. */
    USER("user", "waiting on you"),

    /** The next move is the assistant's, on the next message it is sent. */
    AGENT("agent", "waiting on me");

    private final String id;
    private final String label;

    AwaitedFrom(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    /** How the page says it. */
    public String label() {
        return label;
    }

    public boolean isUser() {
        return this == USER;
    }

    /**
     * The actor named, or {@code null} if it named nothing recognisable.
     * <p>
     * Unlike a stage, an unreadable actor is cheap to get wrong, so callers default it rather than
     * refuse the whole transition — see {@code TaskState.applied}.
     */
    public static AwaitedFrom from(String actor) {
        if (actor == null) {
            return null;
        }
        String normalized = actor.strip().toLowerCase(Locale.ROOT);
        for (AwaitedFrom candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
