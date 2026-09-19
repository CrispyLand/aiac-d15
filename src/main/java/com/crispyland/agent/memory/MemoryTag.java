package com.crispyland.agent.memory;

import java.util.Locale;

/**
 * The routing table: every tag the extractor may emit, and the layer it lands in.
 * <p>
 * This enum is the answer to "what is saved where, and who decided". The model's only job is to
 * label a line; where that label goes is fixed here, in Java, and cannot be argued with at
 * runtime. The alternative — letting the extractor write straight into a store — makes the
 * lifetime of a piece of memory a thing a language model improvises per turn, which is fine until
 * the turn it improvises differently.
 * <p>
 * The tags do not map one-to-one onto layers. {@link #TASK} and {@link #DECISION} both land in
 * working memory, differing only in what happens when the task closes — the one piece of routing
 * that is not a straight lookup, see {@link #promoteAs()}. {@link #STAGE} lands in no layer at
 * all: it describes the job rather than adding to what is known, and goes to the state machine.
 */
public enum MemoryTag {

    /** Scratch for the job in hand. Dies when the task closes, and is meant to. */
    TASK("task", MemoryLayer.WORKING, null,
            "the job in hand: its subject, constraints, numbers and current state"),

    /**
     * Something settled, but kept in working memory until the task that settled it is closed.
     * <p>
     * The obvious implementation writes this straight to long-term, and it is wrong. Long-term is
     * shared by every branch; a decision written there while the task is still open appears inside
     * the forks that exist precisely to disagree with it, and each fork then argues against a
     * position it is simultaneously told is established. Holding it task-scoped until the task
     * closes is what keeps a fork comparison honest.
     */
    DECISION("decision", MemoryLayer.WORKING, LongTermKind.DECISION,
            "something settled about that job and no longer under discussion"),

    /** Who the user is. Not under negotiation, so it is safe to write immediately. */
    PROFILE("profile", MemoryLayer.LONG_TERM, LongTermKind.PROFILE,
            "who the user is: name, role, languages, how they want to be answered"),

    /** Durable facts about the user's world, as distinct from the task in front of them. */
    KNOWLEDGE("knowledge", MemoryLayer.LONG_TERM, LongTermKind.KNOWLEDGE,
            "durable facts about their world that outlast this task"),

    /**
     * Where the job itself stands — not a memory layer at all, and the one tag here that is
     * routed to the state machine instead of to a store.
     * <p>
     * It rides this call rather than getting one of its own because the question "did that message
     * move the task on?" needs the same sentence in front of you that the memory questions do, and
     * a second call would double the per-turn request count against an 8k tokens-per-minute ceiling
     * to re-read text this one already has.
     * <p>
     * Being in this enum is also what keeps the extractor's tag menu honest: the menu is generated
     * from these values, so a tag the prompt offers is by construction a tag something handles.
     */
    STAGE("stage", null, null,
            "where the JOB stands and what is being done about it, as opposed to what is known"),

    /**
     * Explicitly nothing. Having a name for "I read this and there is nothing to keep" is what
     * lets silence be an answer rather than a suspected failure — see {@code MemoryRouter}.
     */
    NONE("none", null, null, "nothing worth keeping in this message");

    private final String id;
    private final MemoryLayer destination;
    private final LongTermKind promoteAs;
    private final String what;

    MemoryTag(String id, MemoryLayer destination, LongTermKind promoteAs, String what) {
        this.id = id;
        this.destination = destination;
        this.promoteAs = promoteAs;
        this.what = what;
    }

    /** The literal tag the extractor is asked to emit. */
    public String id() {
        return id;
    }

    /** Which layer a line with this tag is written to, or {@code null} for {@link #NONE}. */
    public MemoryLayer destination() {
        return destination;
    }

    /**
     * The long-term bucket this line eventually belongs in, or {@code null} if it never leaves
     * the layer it starts in. For {@link #DECISION} this is what it is promoted to on task close;
     * for {@link #PROFILE} and {@link #KNOWLEDGE} it is simply where it goes now.
     */
    public LongTermKind promoteAs() {
        return promoteAs;
    }

    /** True when this line waits in working memory for the task to close before being kept. */
    public boolean heldUntilTaskCloses() {
        return destination == MemoryLayer.WORKING && promoteAs != null;
    }

    /**
     * True for the one tag that describes the task rather than adds to what is remembered.
     * <p>
     * Distinct from {@link #NONE} even though both have no {@link #destination()}: NONE means there
     * is nothing to do with the line, whereas this means there is something to do with it somewhere
     * that is not a memory layer. Collapsing them would make every stage line the model emits
     * disappear into the same silent branch that discards a message about the weather.
     */
    public boolean isTaskState() {
        return this == STAGE;
    }

    /** What the extractor is told this tag means — the prompt and the UI read the same text. */
    public String what() {
        return what;
    }

    /**
     * The tag named by an extractor line, or {@code null} if it named nothing recognisable.
     * <p>
     * Null rather than a nearest match, for the same reason {@link LongTermKind#from} refuses one.
     * Guessing that {@code personal} meant {@code profile} is how a typo earns a line a lifetime
     * nobody chose for it, and a wrongly-kept line is much harder to notice than a missing one.
     */
    public static MemoryTag from(String tag) {
        if (tag == null) {
            return null;
        }
        String normalized = tag.strip().toLowerCase(Locale.ROOT);
        for (MemoryTag candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
