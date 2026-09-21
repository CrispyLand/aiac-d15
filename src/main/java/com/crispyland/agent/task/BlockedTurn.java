package com.crispyland.agent.task;

import java.util.Locale;

/**
 * What a turn should do when {@link StageGate} says it is asking for work the stage has not
 * reached.
 * <p>
 * Two doors, for the same reason {@link PausedTurn} has two: the gate is reading a record of where
 * the work has got to, and a record can simply be behind. The person who typed the message knows
 * whether they have settled the plan; the machine only knows whether anybody pressed the button. So
 * it stops, says what it thinks, and lets them answer — rather than either blocking them outright
 * or deciding for itself that it has heard enough planning.
 * <p>
 * Note what neither door does: <em>neither</em> of them lets the model move the task. Approving is
 * {@link TaskState.Authority#HUMAN} and sending anyway leaves the stage exactly where it was. That
 * is what stops this being a pause with extra steps — the gate cannot be talked past, only stepped
 * around by someone with the authority to say the stage is wrong.
 */
public enum BlockedTurn {

    /**
     * The default. Nothing is answered; the page shows what was stopped and offers the two ways on.
     */
    REFUSE("refuse"),

    /**
     * Move the task out of planning first, then answer. The approval a plan was always supposed to
     * get, given explicitly by the person rather than inferred by the assistant.
     */
    APPROVE("approve"),

    /**
     * Answer it, and leave the task in planning.
     * <p>
     * Not a loophole — an admission that this is bookkeeping. A one-off question that happens to
     * want code in the reply does not mean the plan is settled, and forcing an approval to get an
     * answer would teach people to approve plans they have not read, which is worse than the gate
     * not existing. The stage stays where it was, so the next message is gated again.
     */
    ANYWAY("anyway");

    private final String id;

    BlockedTurn(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** What the page posts, or {@link #REFUSE} for anything unrecognised — the safe default. */
    public static BlockedTurn from(String action) {
        if (action == null) {
            return REFUSE;
        }
        String normalized = action.strip().toLowerCase(Locale.ROOT);
        for (BlockedTurn candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return REFUSE;
    }
}
