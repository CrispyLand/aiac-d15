package com.crispyland.agent.memory;

import com.crispyland.agent.task.TaskState;
import java.util.List;

/**
 * Everything remembered, sorted into the layers of {@link MemoryLayer}.
 * <p>
 * This is the whole memory model in one value: what the planner is handed, and therefore the
 * complete list of things that can possibly reach the model. If it is not in here, the model
 * does not know it.
 * <p>
 * The layers are deliberately separate fields rather than one merged blob. Merging would force
 * a single lifetime on all of it, and the lifetimes are the entire point — {@code recent} is
 * discarded when the chat is reset, {@code working} when the task closes, and {@code longTerm}
 * survives both. Keeping them apart is also what makes "which layer did this answer come from?"
 * a question with an answer.
 *
 * {@code task} sits in here despite not being a layer, because the rule above is the stronger
 * claim: if it can reach the model it has to be in this value. It is the other half of
 * {@code working} — same branch, same task, discarded at the same moment — differing only in that
 * it says where the job is rather than what the job established, and that only one of the two has
 * moves that can be illegal.
 *
 * @param longTerm what is known about the visitor themselves, from before this conversation began
 * @param summary  notes standing in for messages already folded out of {@code recent} — short-term
 * @param working  the current task's key/value scratch space, scoped to the task and the branch
 * @param task     where that task has got to, and whose move is next
 * @param recent   the tail of the transcript still held verbatim, oldest first — short-term
 */
public record MemoryState(LongTermMemory longTerm, Summary summary, Facts working, TaskState task,
                          List<Message> recent) {

    public static final MemoryState EMPTY = new MemoryState(LongTermMemory.EMPTY, Summary.EMPTY,
            Facts.EMPTY, TaskState.EMPTY, List.of());

    public MemoryState {
        longTerm = (longTerm == null) ? LongTermMemory.EMPTY : longTerm;
        summary = (summary == null) ? Summary.EMPTY : summary;
        working = (working == null) ? Facts.EMPTY : working;
        task = (task == null) ? TaskState.EMPTY : task;
        recent = (recent == null) ? List.of() : List.copyOf(recent);
    }

    /** A dialogue with nothing rewritten yet — the shape most tests and fresh sessions are in. */
    public static MemoryState of(List<Message> recent) {
        return new MemoryState(LongTermMemory.EMPTY, Summary.EMPTY, Facts.EMPTY, TaskState.EMPTY, recent);
    }

    public static MemoryState of(Summary summary, List<Message> recent) {
        return new MemoryState(LongTermMemory.EMPTY, summary, Facts.EMPTY, TaskState.EMPTY, recent);
    }

    public static MemoryState of(Facts working, List<Message> recent) {
        return new MemoryState(LongTermMemory.EMPTY, Summary.EMPTY, working, TaskState.EMPTY, recent);
    }

    public static MemoryState of(LongTermMemory longTerm, List<Message> recent) {
        return new MemoryState(longTerm, Summary.EMPTY, Facts.EMPTY, TaskState.EMPTY, recent);
    }

    public static MemoryState of(TaskState task, List<Message> recent) {
        return new MemoryState(LongTermMemory.EMPTY, Summary.EMPTY, Facts.EMPTY, task, recent);
    }
}
