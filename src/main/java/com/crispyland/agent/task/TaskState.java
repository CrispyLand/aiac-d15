package com.crispyland.agent.task;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the task is, what is being done, and whose move is next.
 * <p>
 * This is the answer to <em>where are we</em>, which is a different question from the one memory
 * answers. Memory holds what is known; this holds what is underway. It is also what makes a paused
 * conversation resumable without re-explaining it: {@link #step} and {@link #next} are the two
 * lines that would otherwise have to be typed again after a break.
 * <p>
 * <strong>Paused is not a stage.</strong> You pause <em>in</em> a stage and resume <em>into</em>
 * it, so it is a flag beside the stage rather than a value of it. Modelling it as a stage would
 * need a hidden "the stage I was in before" field to get back out again, and a machine with a
 * shadow variable recording where it came from has stopped being a state machine.
 * <p>
 * Task state shares working memory's scope and its lifetime, so it is not a fourth memory layer —
 * it is the other half of the one that already exists. It still travels as its own prompt block
 * and its own budget line, because "where are we" and "what do we know" fail differently and a
 * model handed one merged block has to work out which half it is reading.
 *
 * @param revision how many times the state has moved; 0 means no task has started, which is what
 *                 {@link #isPresent()} reports and what keeps the block off the prompt entirely
 *                 until there is something to say
 */
public record TaskState(TaskStage stage, String step, String next, AwaitedFrom awaiting,
                        boolean paused, int revision) {

    /** No task yet. Stage reads {@code PLANNING} because that is where one would start. */
    public static final TaskState EMPTY =
            new TaskState(TaskStage.PLANNING, "", "", AwaitedFrom.USER, false, 0);

    /** The keys the extractor emits after {@code stage/}, and the ones the form posts. */
    public static final String STAGE_FIELD = "stage";
    public static final String STEP_FIELD = "step";
    public static final String NEXT_FIELD = "next";
    public static final String WAITING_FIELD = "waiting";

    /** Who is asking for the move. The machine trusts these two differently. */
    public enum Authority {

        /** The model, via the {@code stage} lines on the turn's extraction call. */
        MODEL,

        /** A person, via a button. Bound by the transition table, but not by anything else. */
        HUMAN
    }

    /**
     * A proposed move. Any field may be {@code null}, meaning "leave this one alone" — the
     * extractor is asked for what changed rather than for the whole state, for the same reason
     * {@code Facts} upserts rather than replaces.
     */
    public record Proposal(TaskStage stage, String step, String next, AwaitedFrom awaiting) {

        public Proposal {
            step = blankToNull(step);
            next = blankToNull(next);
        }

        /** A bare stage change, which is what a button posts. */
        public static Proposal toStage(TaskStage stage) {
            return new Proposal(stage, null, null, null);
        }

        /**
         * A proposal assembled from the extractor's {@code stage/…} lines.
         * <p>
         * The four field names are the vocabulary the model is given, and they are read
         * asymmetrically on purpose. An unreadable {@code stage} becomes {@code null}, which means
         * "leave the stage alone" and costs nothing; an unreadable {@code waiting} does the same.
         * Neither is allowed to invent a value, because a guess here is a guess about where the
         * task is, and the machine's whole job is to not do that.
         */
        public static Proposal from(Map<String, String> fields) {
            if (fields == null || fields.isEmpty()) {
                return new Proposal(null, null, null, null);
            }
            return new Proposal(TaskStage.from(fields.get(STAGE_FIELD)), fields.get(STEP_FIELD),
                    fields.get(NEXT_FIELD), AwaitedFrom.from(fields.get(WAITING_FIELD)));
        }

        public boolean isEmpty() {
            return stage == null && step == null && next == null && awaiting == null;
        }
    }

    /**
     * What the machine did with a proposal.
     * <p>
     * The refused case carries a reason rather than just failing quietly. An illegal transition is
     * the most interesting thing the model can do here — it means the model's idea of where the
     * task is has drifted from the machine's — and a refusal nobody can read is indistinguishable
     * from a proposal that never happened.
     *
     * @param moved false when the state came back unchanged, whether refused or simply a no-op
     * @param why   a sentence naming what happened, for the log and the page
     */
    public record Transition(TaskState state, boolean moved, boolean refused, String why) {

        static Transition unchanged(TaskState state, String why) {
            return new Transition(state, false, false, why);
        }

        static Transition refusal(TaskState state, String why) {
            return new Transition(state, false, true, why);
        }
    }

    public TaskState {
        stage = (stage == null) ? TaskStage.PLANNING : stage;
        step = (step == null) ? "" : step.strip();
        next = (next == null) ? "" : next.strip();
        awaiting = (awaiting == null) ? AwaitedFrom.USER : awaiting;
    }

    /** True once a task has actually started. Until then the block is not worth a single token. */
    public boolean isPresent() {
        return revision > 0;
    }

    public boolean isDone() {
        return stage == TaskStage.DONE;
    }

    /** True when the task is waiting on the person, and not paused — what the indicator shows. */
    public boolean waitingOnUser() {
        return !paused && awaiting.isUser();
    }

    /**
     * Applies a proposal, or refuses it and says why.
     * <p>
     * The three refusals, in the order they are checked:
     * <ol>
     *   <li><strong>Paused.</strong> A paused task that the model keeps advancing is not paused.
     *       Most paused turns never reach here, because {@code Agent.handle} refuses them before
     *       the call; this is the check that holds for a {@link PausedTurn#ASIDE}, which does get
     *       answered — with the full state block — and must still leave the task where it was.</li>
     *   <li><strong>Illegal.</strong> Straight off {@link TaskStage#canMoveTo}. Refused wholesale
     *       rather than partly: a step written to describe {@code done} is not a sensible step to
     *       file under {@code validation}, so keeping the text while dropping the stage stores a
     *       description of somewhere the task is not.</li>
     *   <li><strong>Not the model's to make.</strong> {@link TaskStage#requiresHuman()}.</li>
     * </ol>
     * A proposal that names the current stage and changes nothing else is a no-op rather than a
     * refusal — most turns are exactly that, and counting them as refusals would bury the real ones.
     */
    public Transition apply(Proposal proposal, Authority by) {
        if (proposal == null || proposal.isEmpty()) {
            return Transition.unchanged(this, "nothing proposed");
        }
        TaskStage target = (proposal.stage() == null) ? stage : proposal.stage();

        if (paused && by == Authority.MODEL) {
            return Transition.refusal(this, "the task is paused; resume it before it moves on");
        }
        if (!stage.canMoveTo(target)) {
            return Transition.refusal(this, "%s cannot move to %s — legal moves are %s"
                    .formatted(stage.id(), target.id(), moveList()));
        }
        if (target != stage && target.requiresHuman() && by == Authority.MODEL) {
            return Transition.refusal(this,
                    "only a person may move a task to %s".formatted(target.id()));
        }

        TaskState moved = new TaskState(target,
                (proposal.step() == null) ? step : proposal.step(),
                (proposal.next() == null) ? next : proposal.next(),
                (proposal.awaiting() == null) ? awaiting : proposal.awaiting(),
                paused, revision + 1);
        if (moved.sameContentAs(this)) {
            return Transition.unchanged(this, "already there");
        }
        return new Transition(moved, true, false, "%s → %s".formatted(stage.id(), target.id()));
    }

    /**
     * Stops here. The stage is kept, because resuming has to land back in it — that is the
     * difference between pausing a task and abandoning one.
     */
    public TaskState pause() {
        return paused ? this : new TaskState(stage, step, next, awaiting, true, revision + 1);
    }

    public TaskState resume() {
        return paused ? new TaskState(stage, step, next, awaiting, false, revision + 1) : this;
    }

    /**
     * The block as the model is sent it.
     * <p>
     * Written as instructions about the job rather than as a dump of fields, because the second
     * line is the one doing the work: told the current step, a resumed conversation carries on from
     * it instead of asking what we were doing.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("- stage: ").append(stage.id()).append(" — ").append(stage.what());
        if (!step.isEmpty()) {
            text.append("\n- current step: ").append(step);
        }
        if (!next.isEmpty()) {
            text.append("\n- expected next action (").append(awaiting.id()).append("): ").append(next);
        }
        if (paused) {
            text.append("\n- paused: the task is on hold in ").append(stage.id())
                    .append("; do not treat it as finished or restarted");
        }
        return text.toString();
    }

    private boolean sameContentAs(TaskState other) {
        return stage == other.stage && step.equals(other.step) && next.equals(other.next)
                && awaiting == other.awaiting && paused == other.paused;
    }

    private String moveList() {
        return String.join(", ", stage.moves().stream().map(TaskStage::id).toList());
    }

    /**
     * The spellings a model reaches for when it means "nothing".
     * <p>
     * Asked for only the fields a message actually changed, it sometimes answers the question
     * instead of skipping it — observed live emitting {@code stage/next: none} for a step with no
     * follow-up. Taken at face value that renders as "waiting on me: none", which reads as an
     * instruction to go and do something called "none". Every one of these says less than saying
     * nothing, so they are treated as the omission they were meant to be.
     */
    private static final Set<String> MEANS_NOTHING = Set.of("none", "n/a", "na", "null", "-");

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return (stripped.isEmpty() || MEANS_NOTHING.contains(stripped.toLowerCase(Locale.ROOT)))
                ? null : stripped;
    }
}
