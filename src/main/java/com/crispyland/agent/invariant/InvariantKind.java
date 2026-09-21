package com.crispyland.agent.invariant;

import java.util.Locale;

/**
 * What sort of commitment an invariant is.
 * <p>
 * The four are not a taxonomy for its own sake — they are four different answers to "who is allowed
 * to lift this, and what breaks if it is lifted quietly". {@link #STACK} is usually a team's to
 * revisit; {@link #BUSINESS} very often is not, and an agent that cannot tell them apart will
 * negotiate over the wrong one.
 * <p>
 * Like {@link com.crispyland.agent.memory.LongTermKind}, the names double as the tag the UI posts
 * and the heading the prompt block groups under, so labelling is a lookup rather than a judgement.
 */
public enum InvariantKind {

    /** How the system is shaped: boundaries, layering, what talks to what. */
    ARCHITECTURE("Architecture", "how the system is shaped"),

    /** Calls already made and not being reopened, with the reasoning attached. */
    TECHNICAL("Technical decisions", "calls already made and not reopened"),

    /** What may and may not be brought in: languages, libraries, services, infrastructure. */
    STACK("Stack constraints", "what may and may not be brought in"),

    /** What the domain itself does not permit, whatever the code could technically do. */
    BUSINESS("Business rules", "what the domain does not permit");

    private final String label;
    private final String tagline;

    InvariantKind(String label, String tagline) {
        this.label = label;
        this.tagline = tagline;
    }

    /** Stable id for form fields, CSS hooks and the stored file. */
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
     * The kind named, or {@code null} for anything unrecognised.
     * <p>
     * Null rather than a default, for the same reason {@code LongTermKind.from} refuses to guess:
     * a default here files a business rule under stack constraints, where it reads as negotiable.
     */
    public static InvariantKind from(String kind) {
        if (kind == null) {
            return null;
        }
        String normalized = kind.strip().toLowerCase(Locale.ROOT);
        for (InvariantKind candidate : values()) {
            if (candidate.id().equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
