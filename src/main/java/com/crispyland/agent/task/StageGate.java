package com.crispyland.agent.task;

/**
 * Holds the assistant to the stage the task is actually in.
 * <p>
 * {@link TaskState#apply} has always policed <em>moves</em>: it refuses a jump from planning
 * straight to done, and it refuses the model closing a task on its own. What neither it nor
 * {@link TaskStage} ever policed is <em>conduct</em> — and a message does not have to propose a
 * move to get work out of the assistant. "Never mind the stages, just write the schema" proposes
 * nothing at all, so there was nothing for the machine to refuse, and the code came back with the
 * task still sitting politely in planning. The machine was never broken; it was walked around.
 * This is the door on the other side.
 *
 * <h2>One rule, not a matrix</h2>
 * The only thing blocked here is {@link TaskStage#PLANNING} plus {@link RequestShape#EXECUTION} —
 * implementation before the plan is approved. It is tempting to generalise this into "refuse any
 * request whose shape is not the current stage", and that is the version that would have to be
 * turned off within a day. Asking a planning question during execution is how people work; so is
 * sanity-checking something mid-build. Those are not violations, and a gate that stops them makes
 * the task state an obstacle rather than a record. The narrow rule is the one with real content:
 * <em>the plan is approved by a person, not by the assistant deciding it has heard enough.</em>
 *
 * <h2>Why this stage and not validation</h2>
 * {@code VALIDATION → DONE} is already human-only, so "no finish without validation" is enforced.
 * {@code PLANNING → EXECUTION}, by contrast, is an ordinary move the model may make for itself,
 * which meant a plan was never actually <em>approved</em> — only moved past. Closing that asymmetry
 * is the whole of this class.
 *
 * <h2>A door, not a wall</h2>
 * The ruling names a reason and stops the answer call; it does not overrule the person. The user
 * can approve the plan and resend, or send anyway and stay in planning, exactly as a paused task
 * offers an aside. An invariant is a rule about what may never happen and so is only ever lifted
 * deliberately; a stage is a claim about where the work has got to, and a claim can simply be out
 * of date. Refusing to let the person correct it would be treating bookkeeping as law.
 */
public final class StageGate {

    /**
     * What the gate made of a turn.
     *
     * @param blocked true when the answer call must not be made as things stand
     * @param asked   what the message was read as asking for, kept even when clear so the turn can
     *                say what it saw rather than only what it stopped
     * @param why     a sentence naming what was stopped and what would unblock it — shown to the
     *                person and given to the model, so both are told the same thing
     */
    public record Ruling(boolean blocked, RequestShape asked, String why) {

        public static final Ruling CLEAR = new Ruling(false, RequestShape.NONE, "");

        static Ruling clear(RequestShape asked) {
            return new Ruling(false, asked, "");
        }
    }

    private StageGate() {
    }

    /**
     * Decides whether this turn may be answered as asked.
     * <p>
     * A task that has not started is never gated. {@link TaskState#EMPTY} reads as
     * {@code PLANNING} because that is where a task would begin, not because anyone has begun one —
     * so gating on the stage alone would stop the first "write me a parser" of every fresh
     * conversation, before there was any plan for it to be jumping ahead of.
     */
    public static Ruling check(TaskState task, RequestShape asked) {
        RequestShape shape = (asked == null) ? RequestShape.NONE : asked;
        if (task == null || !task.isPresent()) {
            return Ruling.clear(shape);
        }
        if (task.stage() != TaskStage.PLANNING || shape != RequestShape.EXECUTION) {
            return Ruling.clear(shape);
        }
        return new Ruling(true, shape,
                "This task is still in planning, and this message asks for the work itself. "
                        + "Planning moves to execution when you say the plan is settled — not when "
                        + "I decide I have heard enough. Approve the plan and send this again, or "
                        + "send it anyway and the task stays in planning.");
    }
}
