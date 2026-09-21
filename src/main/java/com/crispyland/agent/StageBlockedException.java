package com.crispyland.agent;

import com.crispyland.agent.task.StageGate;
import com.crispyland.agent.task.TaskState;

/**
 * Thrown when a message asks for work the task has not been approved to start.
 * <p>
 * Unlike an invariant breach, this is not an answer. A breach produces something worth keeping —
 * the rule, why it exists, what can be done instead — so it is written into the transcript as the
 * turn's reply. A blocked stage produces nothing: the message is not wrong, it is early, and it
 * will almost certainly be sent again unchanged once the plan is approved. Putting a refusal into
 * the history that is about to be contradicted by the same message succeeding teaches the next turn
 * that the thing was refused when it was not.
 * <p>
 * Unlike {@link TaskPausedException}, the extraction call has already been made and paid for by the
 * time this is thrown, and its memory writes stand. That is not an oversight: what the message
 * asked for is the thing being read off that call, so there is no cheaper place to find out. And
 * the facts in a premature message are still facts — "use Postgres 16, now write the migration"
 * settles the database whether or not the migration gets written.
 * <p>
 * Carries the state and the ruling so the page can name the stage, quote the reason, and offer both
 * doors, rather than telling the user they are stuck.
 */
public class StageBlockedException extends AgentException {

    private final transient TaskState task;
    private final transient StageGate.Ruling ruling;

    public StageBlockedException(TaskState task, StageGate.Ruling ruling) {
        super(ruling.why());
        this.task = task;
        this.ruling = ruling;
    }

    public TaskState task() {
        return task;
    }

    public StageGate.Ruling ruling() {
        return ruling;
    }
}
