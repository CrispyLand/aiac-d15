package com.crispyland.agent.task;

import java.util.Locale;
import java.util.Map;

/**
 * What a message asks the assistant <em>to do</em>, as distinct from where the task already is.
 * <p>
 * {@link TaskStage} has always tracked position and never conduct. It answers "where are we" and
 * refuses illegal moves between stages — but nothing in it ever looked at what the newest message
 * actually wanted. That gap is why "ignore the stages and just give me the code" worked: it never
 * proposed a move, so there was no move to refuse. It walked around the machine rather than
 * breaking it. This type is the missing half of the question, so the pair can be compared.
 * <p>
 * Deliberately the same vocabulary as the stages, minus {@code done}. Asking to finish is not a
 * shape of request the assistant can satisfy on its own — {@link TaskStage#requiresHuman()} already
 * settles that, and a second mechanism saying the same thing is a second thing to keep in sync.
 * <p>
 * {@link #NONE} is the honest and by far the commonest answer. Most messages are questions,
 * corrections, greetings and asides that ask for no stage of work at all, and a type whose default
 * was a guess would put every one of them in front of a gate.
 */
public enum RequestShape {

    /** Asks for thinking, options, an approach — the work of deciding what to do. */
    PLANNING("planning", "asks for an approach, options or a plan"),

    /** Asks for the thing itself: code, a schema, a migration, a draft, the finished artefact. */
    EXECUTION("execution", "asks for the work itself — code, a schema, a draft, the artefact"),

    /** Asks for what exists to be checked, reviewed, tested or critiqued. */
    VALIDATION("validation", "asks for existing work to be checked, reviewed or tested"),

    /** Asks for no stage of the work at all. The ordinary case. */
    NONE("none", "asks for no stage of the work at all");

    private final String id;
    private final String what;

    RequestShape(String id, String what) {
        this.id = id;
        this.what = what;
    }

    /** The literal token the extractor emits. */
    public String id() {
        return id;
    }

    /** What this shape means — the prompt and the page read the same sentence. */
    public String what() {
        return what;
    }

    /**
     * The shape named, or {@link #NONE} for anything unrecognised.
     * <p>
     * {@code NONE} rather than {@code null}, which is the opposite of {@link TaskStage#from} and
     * for the same underlying reason. There, an unreadable value must not move the task, so it
     * becomes "leave it alone". Here, an unreadable value must not <em>stop</em> the turn, so it
     * becomes "nothing was asked for". Both defaults fail towards doing nothing to the user; the
     * types differ because doing nothing means different things on the two sides.
     */
    public static RequestShape from(String shape) {
        if (shape == null) {
            return NONE;
        }
        String normalized = shape.strip().toLowerCase(Locale.ROOT);
        for (RequestShape candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return NONE;
    }

    /**
     * The shape found among a turn's {@code stage/…} fields, which is where it arrives from.
     * <p>
     * Its own reader rather than a field on {@code Proposal}, because the two are answers to
     * different questions and only one of them is allowed to change anything.
     */
    public static RequestShape in(Map<String, String> fields) {
        return (fields == null) ? NONE : from(fields.get(TaskState.ASKS_FOR_FIELD));
    }
}
