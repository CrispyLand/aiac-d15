package com.crispyland.agent.task;

import java.util.Locale;

/**
 * What a turn should do when it lands on a paused task.
 * <p>
 * Pausing has to stop the work, but the work is not the only thing a person might want to say. A
 * pause that blocks every message blocks "what did we decide about the deadline?" along with
 * "carry on" — and answering a question is not resuming a task. So there are two doors, and the
 * person picks which one they meant rather than the machine guessing from the text.
 * <p>
 * Named rather than a boolean for the same reason {@link TaskState.Authority} and
 * {@link AwaitedFrom} are:
 * {@code handle(scope, persona, input, config, false)} at a call site says nothing about which
 * false it is.
 */
public enum PausedTurn {

    /**
     * The default. Nothing is sent, nothing is charged, and the page says so. This is what a
     * pause means when the message is about the task.
     */
    REFUSE("refuse"),

    /**
     * Answer it, but leave the task exactly where it was. The turn runs normally — with the task
     * block still on the prompt, so the answer knows where things stand — and any transition the
     * model proposes on the way is refused by {@code TaskState.apply}, which is what keeps this an
     * aside rather than a resume by the back door.
     * <p>
     * The honest cost: an aside is still a turn, so it is charged, it joins the transcript, and
     * facts extracted from it land in the same working memory as the task's. Only the state
     * machine is frozen, not the conversation.
     */
    ASIDE("aside");

    private final String id;

    PausedTurn(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** What the page posts, or {@link #REFUSE} for anything unrecognised — the safe default. */
    public static PausedTurn from(String action) {
        if (action == null) {
            return REFUSE;
        }
        String normalized = action.strip().toLowerCase(Locale.ROOT);
        for (PausedTurn candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return REFUSE;
    }
}
