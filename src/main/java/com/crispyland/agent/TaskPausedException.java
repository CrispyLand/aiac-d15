package com.crispyland.agent;

import com.crispyland.agent.task.TaskState;

/**
 * Thrown when a message arrives for a task that is paused. Nothing was sent and nothing was stored.
 * <p>
 * The earlier behaviour was to answer the message and quietly refuse only the state transition,
 * which put the refusal in the log and nowhere else. That was a pause in name only. This agent has
 * no loop and no tools: answering <em>is</em> the only thing it does, so a pause that still answers
 * pauses the bookkeeping and nothing the user can see. The page says "nothing advances until you
 * resume", and this is what makes that sentence true.
 * <p>
 * Refusing every message would be its own mistake, though: a pause stops the work, and not every
 * message is the work. So this is a question, not a wall — the page offers both resuming and
 * {@link com.crispyland.agent.task.PausedTurn#ASIDE}, which answers the one message with the task
 * still frozen. The choice sits with the person because working it out from the text would mean
 * paying for a model call to decide whether to make a model call.
 * <p>
 * It carries the state rather than just a string so the page can name the step being resumed into
 * and offer the one-click way back, instead of telling the user they are stuck.
 */
public class TaskPausedException extends AgentException {

    private final transient TaskState task;

    public TaskPausedException(TaskState task) {
        super(message(task));
        this.task = task;
    }

    public TaskState task() {
        return task;
    }

    private static String message(TaskState task) {
        String where = "This task is paused in " + task.stage().id()
                + ", so nothing was sent and nothing was charged.";
        String what = task.step().isBlank() ? where
                : where + " Resume to carry on with: " + task.step();
        return what + " If this message is about something else, send it as an aside — "
                + "it gets answered and the task stays where it is.";
    }
}
