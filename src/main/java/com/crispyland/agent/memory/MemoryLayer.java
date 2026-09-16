package com.crispyland.agent.memory;

import java.util.Locale;

/**
 * The three kinds of thing the agent remembers, separated by how long each should outlive the
 * turn that created it.
 * <p>
 * The distinction that matters is <em>not</em> what the information is about. "Postgres 15" could
 * legitimately sit in any of these layers; what decides is scope and lifetime. A single
 * undifferentiated memory has to pick one lifetime for everything and is therefore wrong twice
 * over — it either forgets your name along with the transcript, or it carries a half-finished
 * task into an unrelated conversation forever.
 * <p>
 * Read as a hierarchy of permanence: {@link #SHORT_TERM} is the literal dialogue and dies with
 * it, {@link #WORKING} is scratch space for the job in hand, {@link #LONG_TERM} is what should
 * still be true next week. Information is promoted inward as it settles — a decision lives in
 * working memory while it is still being argued about, and only becomes long-term once the task
 * that produced it is closed.
 * <p>
 * That promotion rule is load-bearing rather than tidy-minded. Long-term memory is shared by
 * every branch of every conversation, so writing a decision there while it is still under
 * negotiation would leak it sideways into the forks that exist precisely to disagree with it.
 */
public enum MemoryLayer {

    SHORT_TERM("Short-term", "The dialogue itself",
            "this branch of this conversation", "until the chat is reset",
            "The last few turns verbatim, plus notes standing in for the ones already folded away."),

    WORKING("Working", "Data for the task in hand",
            "the current task", "until the task is finished",
            "Goal, constraints and decisions for the job being done right now — cleared when it ends."),

    LONG_TERM("Long-term", "Profile, settled decisions, knowledge",
            "you, across every conversation", "until you delete it",
            "What stays true after the conversation ends, and is still there in a brand new chat.");

    private final String label;
    private final String holds;
    private final String scope;
    private final String lifetime;
    private final String tagline;

    MemoryLayer(String label, String holds, String scope, String lifetime, String tagline) {
        this.label = label;
        this.holds = holds;
        this.scope = scope;
        this.lifetime = lifetime;
        this.tagline = tagline;
    }

    /** Stable id for URLs, form fields and CSS hooks — {@code short-term}, {@code long-term}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public String label() {
        return label;
    }

    public String holds() {
        return holds;
    }

    /** What the layer is keyed by — the answer to "whose memory is this?". */
    public String scope() {
        return scope;
    }

    /** What ends it. The other half of the only distinction that actually separates the layers. */
    public String lifetime() {
        return lifetime;
    }

    public String tagline() {
        return tagline;
    }
}
