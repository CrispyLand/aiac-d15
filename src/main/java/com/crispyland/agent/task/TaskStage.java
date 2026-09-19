package com.crispyland.agent.task;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where a task is, and the only moves it is allowed to make from there.
 * <p>
 * The table below is the whole state machine. It lives in Java, as a fixed lookup, for the same
 * reason {@code MemoryTag}'s routing table does: the model may propose a stage, but what follows
 * from a stage is not a thing it gets to improvise per turn. A machine whose transitions are
 * decided by whatever came back from the last call is not a state machine, it is a variable.
 * <p>
 * <strong>The back-edges are the point.</strong> {@code VALIDATION → EXECUTION} is what makes
 * validation mean anything — a validation stage that can only be followed by {@code DONE} cannot
 * fail, so it is theatre. {@code EXECUTION → PLANNING} is the other one that has to exist: it is
 * what happens when the work reveals the plan was wrong, which is most plans.
 * <p>
 * Self-transitions are legal and are the common case. Most turns of a conversation do not move the
 * task anywhere, and a machine that treated "still in execution" as an illegal repeat would refuse
 * every ordinary message.
 * <p>
 * {@link #DONE} is terminal and one-way. Abandoning a task is not a transition — it is the
 * {@code /task/new} button, which discards the task rather than moving it.
 */
public enum TaskStage {

    /** Working out what to do. Nothing has been built, so there is nothing to validate yet. */
    PLANNING("planning", "working out what to do, before anything is built"),

    /** Doing the work planning settled on. */
    EXECUTION("execution", "doing the work that planning settled on"),

    /** Checking what execution produced against what planning asked for. */
    VALIDATION("validation", "checking what execution produced against what planning asked for"),

    /** Finished and closed. Nothing follows it. */
    DONE("done", "finished and closed; nothing follows it");

    private static final Map<TaskStage, Set<TaskStage>> MOVES = new EnumMap<>(TaskStage.class);

    static {
        MOVES.put(PLANNING, EnumSet.of(PLANNING, EXECUTION));
        MOVES.put(EXECUTION, EnumSet.of(EXECUTION, VALIDATION, PLANNING));
        MOVES.put(VALIDATION, EnumSet.of(VALIDATION, EXECUTION, PLANNING, DONE));
        MOVES.put(DONE, EnumSet.of(DONE));
    }

    private final String id;
    private final String what;

    TaskStage(String id, String what) {
        this.id = id;
        this.what = what;
    }

    /** The literal token the extractor emits and the form posts back. */
    public String id() {
        return id;
    }

    /** What this stage means — the prompt and the page read the same sentence. */
    public String what() {
        return what;
    }

    /** True when {@code target} is reachable from here in one move. */
    public boolean canMoveTo(TaskStage target) {
        return target != null && MOVES.get(this).contains(target);
    }

    /** Everything reachable from here in one move, including staying put. */
    public Set<TaskStage> moves() {
        return MOVES.get(this);
    }

    /**
     * True for a stage only a person may move the task into.
     * <p>
     * Only {@link #DONE}, and not because closing is dangerous in itself: closing runs the task's
     * settled lines into long-term memory, where every branch will read them and no later message
     * will correct them. The model guessing wrong about whether a job is finished writes permanent
     * memory on a guess, and that is not visible and not undone by the next turn. The same argument
     * made task-finish a button rather than an inference in the first place.
     */
    public boolean requiresHuman() {
        return this == DONE;
    }

    public boolean isTerminal() {
        return this == DONE;
    }

    /**
     * The stage named, or {@code null} for anything unrecognised.
     * <p>
     * Null rather than a nearest match. Reading {@code executing} as {@link #EXECUTION} looks
     * harmless until the day it reads {@code completed} as {@link #DONE} and closes a task nobody
     * finished.
     */
    public static TaskStage from(String stage) {
        if (stage == null) {
            return null;
        }
        String normalized = stage.strip().toLowerCase(Locale.ROOT);
        for (TaskStage candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
