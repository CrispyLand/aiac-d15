package com.crispyland.agent.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.task.TaskState.Authority;
import com.crispyland.agent.task.TaskState.Proposal;
import com.crispyland.agent.task.TaskState.Transition;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskStateTest {

    @Test
    void aFreshConversationHasNoTaskAndThereforeNoBlockToPayFor() {
        assertThat(TaskState.EMPTY.isPresent()).isFalse();
        assertThat(TaskState.EMPTY.stage()).isEqualTo(TaskStage.PLANNING);
    }

    @Test
    void theModelMayMoveTheTaskForwardOneStepAtATime() {
        Transition moved = inExecution().apply(
                new Proposal(TaskStage.VALIDATION, "checking the migration",
                        "run it against staging", AwaitedFrom.AGENT), Authority.MODEL);

        assertThat(moved.moved()).isTrue();
        assertThat(moved.state().stage()).isEqualTo(TaskStage.VALIDATION);
        assertThat(moved.state().step()).isEqualTo("checking the migration");
        assertThat(moved.state().isPresent()).isTrue();
    }

    @Test
    void onlyAPersonMayLetTheTaskOutOfPlanning() {
        // The move the model most wants to make and the one it must not: saying work has started
        // is how a plan gets approved by nobody. It is legal on the table — it is just not the
        // model's word to give.
        assertThat(TaskStage.PLANNING.canMoveTo(TaskStage.EXECUTION)).isTrue();

        Transition refused =
                TaskState.EMPTY.apply(Proposal.toStage(TaskStage.EXECUTION), Authority.MODEL);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.state()).isEqualTo(TaskState.EMPTY);
        assertThat(refused.why()).contains("only a person").contains("planning").contains("execution");
    }

    @Test
    void rollingBackIntoExecutionIsStillTheModelsToDo() {
        // Same destination as the move above, opposite answer — which is why the human-only rule
        // is an edge and not a property of the stage being entered. A failed check that cannot
        // send the work back is a check with no consequence.
        Transition back = inValidation()
                .apply(Proposal.toStage(TaskStage.EXECUTION), Authority.MODEL);

        assertThat(back.moved()).isTrue();
        assertThat(back.state().stage()).isEqualTo(TaskStage.EXECUTION);
    }

    @Test
    void planningCannotJumpStraightToDone() {
        // The single most important thing the table forbids: a task that was never executed and
        // never validated cannot be declared finished, which is exactly what a model will try on
        // the turn somebody says "great, thanks".
        Transition refused = TaskState.EMPTY
                .apply(Proposal.toStage(TaskStage.DONE), Authority.MODEL);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.state()).isEqualTo(TaskState.EMPTY);
        assertThat(refused.why()).contains("planning cannot move to done");
    }

    @Test
    void planningCannotSkipToValidationBecauseThereIsNothingToValidateYet() {
        assertThat(TaskStage.PLANNING.canMoveTo(TaskStage.VALIDATION)).isFalse();
    }

    @Test
    void validationCanSendTheTaskBackToExecutionOrValidationIsTheatre() {
        // Without this edge, validation has exactly one exit and therefore cannot fail. A stage
        // that can only be passed is not a check.
        assertThat(TaskStage.VALIDATION.canMoveTo(TaskStage.EXECUTION)).isTrue();
        assertThat(TaskStage.EXECUTION.canMoveTo(TaskStage.PLANNING)).isTrue();
    }

    @Test
    void onlyAPersonMayCloseTheTask() {
        // Closing promotes the task's settled lines into long-term memory, where every branch
        // reads them and no later message corrects them. A model guessing "done" writes permanent
        // memory on a guess.
        TaskState validating = inValidation();

        assertThat(validating.apply(Proposal.toStage(TaskStage.DONE), Authority.MODEL).refused())
                .isTrue();
        assertThat(validating.apply(Proposal.toStage(TaskStage.DONE), Authority.HUMAN).state().isDone())
                .isTrue();
    }

    @Test
    void aHumanIsStillBoundByTheTransitionTable() {
        // The button is an override of authority, not of legality — otherwise the table only
        // describes what the model does and the machine has two sets of rules.
        assertThat(TaskState.EMPTY.apply(Proposal.toStage(TaskStage.DONE), Authority.HUMAN).refused())
                .isTrue();
    }

    @Test
    void stayingInTheSameStageIsLegalBecauseMostTurnsDoNotMoveTheTask() {
        TaskState executing = inExecution();

        Transition sideways = executing.apply(
                new Proposal(TaskStage.EXECUTION, "still writing the migration", null, null),
                Authority.MODEL);

        assertThat(sideways.moved()).isTrue();
        assertThat(sideways.state().step()).isEqualTo("still writing the migration");
    }

    @Test
    void aProposalThatChangesNothingIsANoOpRatherThanARefusal() {
        // Counting the ordinary turn as a refusal would bury the refusals that mean something.
        TaskState executing = inExecution();

        Transition same = executing.apply(Proposal.toStage(TaskStage.EXECUTION), Authority.MODEL);

        assertThat(same.moved()).isFalse();
        assertThat(same.refused()).isFalse();
        assertThat(same.state()).isEqualTo(executing);
    }

    @Test
    void anOmittedFieldLeavesThatFieldAloneRatherThanClearingIt() {
        // The extractor is asked for what changed, not for the whole state — same upsert rule as
        // working memory, and for the same reason: the shorter reply is the one it gets right.
        TaskState executing = inExecution();

        TaskState after = executing
                .apply(new Proposal(null, "rewriting the migration", null, null), Authority.MODEL)
                .state();

        assertThat(after.stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(after.step()).isEqualTo("rewriting the migration");
        assertThat(after.next()).isEqualTo("confirm the index name");
    }

    @Test
    void aFieldTheModelFilledInWithTheWordNothingIsTreatedAsOmitted() {
        // Observed live: told to send only what changed, the model answered `stage/next: none` for
        // a step with no follow-up. Stored literally that renders as "waiting on me: none", which
        // reads as an instruction to go and do something called "none" — worse than the silence it
        // was supposed to be, and it would overwrite a real next action that still stands.
        TaskState executing = inExecution();

        TaskState after = executing.apply(Proposal.from(Map.of(
                TaskState.STEP_FIELD, "rewriting the migration",
                TaskState.NEXT_FIELD, "none")), Authority.MODEL).state();

        assertThat(after.step()).isEqualTo("rewriting the migration");
        assertThat(after.next()).isEqualTo("confirm the index name");
    }

    @Test
    void aProposalThatSaysNothingButNothingMovesNothing() {
        TaskState executing = inExecution();

        assertThat(Proposal.from(Map.of(TaskState.NEXT_FIELD, "n/a")).isEmpty()).isTrue();
        assertThat(executing.apply(Proposal.from(Map.of(TaskState.STEP_FIELD, "  ")),
                Authority.MODEL).moved()).isFalse();
    }

    @Test
    void pausingKeepsTheStageSoResumingLandsBackInIt() {
        // Paused is a flag beside the stage, not a stage. If it were a stage, leaving it would
        // need a hidden record of where the task came from.
        TaskState paused = inExecution().pause();

        assertThat(paused.paused()).isTrue();
        assertThat(paused.stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(paused.resume().stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(paused.resume().paused()).isFalse();
    }

    @Test
    void aPausedTaskRefusesToBeMovedByTheModel() {
        // Otherwise pause means nothing: the next message would quietly advance the machine and
        // the person who paused would come back to a task somewhere else.
        Transition refused = inExecution().pause()
                .apply(Proposal.toStage(TaskStage.VALIDATION), Authority.MODEL);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.why()).contains("paused");
    }

    @Test
    void aPausedTaskStillRendersItsStepSoTheDialogueResumesWithoutReExplaining() {
        String block = inExecution().pause().render();

        assertThat(block).contains("stage: execution");
        assertThat(block).contains("current step: writing the migration");
        assertThat(block).contains("expected next action (user): confirm the index name");
        assertThat(block).contains("paused");
    }

    @Test
    void doneIsTerminalBecauseAbandoningATaskIsADifferentButton() {
        assertThat(TaskStage.DONE.moves()).containsExactly(TaskStage.DONE);
        assertThat(TaskStage.DONE.isTerminal()).isTrue();
    }

    @Test
    void anUnrecognisedStageNameIsRefusedRatherThanGuessedAt() {
        // "completed" read as DONE would close a task nobody finished.
        assertThat(TaskStage.from("completed")).isNull();
        assertThat(TaskStage.from("executing")).isNull();
        assertThat(TaskStage.from("EXECUTION")).isEqualTo(TaskStage.EXECUTION);
    }

    @Test
    void theIndicatorOnlyClaimsToBeWaitingOnSomebodyWhileTheTaskIsRunning() {
        assertThat(inExecution().waitingOnUser()).isTrue();
        assertThat(inExecution().pause().waitingOnUser()).isFalse();
    }

    /** HUMAN, because leaving planning is an approval and nothing else in here can give one. */
    private static TaskState inExecution() {
        return TaskState.EMPTY.apply(
                new Proposal(TaskStage.EXECUTION, "writing the migration",
                        "confirm the index name", AwaitedFrom.USER), Authority.HUMAN).state();
    }

    private static TaskState inValidation() {
        return inExecution().apply(Proposal.toStage(TaskStage.VALIDATION), Authority.MODEL).state();
    }
}
