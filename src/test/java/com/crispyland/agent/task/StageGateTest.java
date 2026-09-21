package com.crispyland.agent.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate closes on one combination and opens on every other, and both halves are load-bearing.
 * <p>
 * The blocking test is the day's requirement. The rest are the reason the rule is one line instead
 * of a matrix: a gate that also stopped planning questions during execution, or review requests, or
 * the first message of a conversation, would be switched off within a day and the requirement would
 * go with it.
 */
class StageGateTest {

    private static TaskState underway(TaskStage stage) {
        return new TaskState(stage, "settling the schema", "agree the columns", AwaitedFrom.USER,
                false, 3);
    }

    @Test
    @DisplayName("implementation while the plan is still being agreed is stopped, and says why")
    void implementationWhileThePlanIsStillBeingAgreedIsStopped() {
        StageGate.Ruling ruling =
                StageGate.check(underway(TaskStage.PLANNING), RequestShape.EXECUTION);

        assertThat(ruling.blocked()).isTrue();
        assertThat(ruling.asked()).isEqualTo(RequestShape.EXECUTION);
        // Both halves: what was stopped, and what the person can do about it. A refusal that only
        // states the rule leaves them guessing at the door.
        assertThat(ruling.why()).contains("planning").contains("send");
    }

    @Test
    @DisplayName("a task nobody has started is not jumping ahead of a plan that does not exist")
    void aTaskNobodyHasStartedIsNotJumpingAhead() {
        // TaskState.EMPTY reads as PLANNING because that is where a task would begin. Gating on the
        // stage alone would stop the first real request of every fresh conversation.
        assertThat(StageGate.check(TaskState.EMPTY, RequestShape.EXECUTION).blocked()).isFalse();
        assertThat(StageGate.check(null, RequestShape.EXECUTION).blocked()).isFalse();
    }

    @Test
    @DisplayName("planning questions during planning are the work, not a violation of it")
    void planningQuestionsDuringPlanningAreTheWork() {
        assertThat(StageGate.check(underway(TaskStage.PLANNING), RequestShape.PLANNING).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.PLANNING), RequestShape.NONE).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.PLANNING), RequestShape.VALIDATION).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("once the plan is approved, asking for the work is the whole point")
    void onceThePlanIsApprovedAskingForTheWorkIsThePoint() {
        assertThat(StageGate.check(underway(TaskStage.EXECUTION), RequestShape.EXECUTION).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.VALIDATION), RequestShape.EXECUTION).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.DONE), RequestShape.EXECUTION).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("mid-build planning and mid-build review are how people actually work")
    void midBuildPlanningAndReviewAreHowPeopleWork() {
        assertThat(StageGate.check(underway(TaskStage.EXECUTION), RequestShape.PLANNING).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.EXECUTION), RequestShape.VALIDATION).blocked())
                .isFalse();
        assertThat(StageGate.check(underway(TaskStage.VALIDATION), RequestShape.PLANNING).blocked())
                .isFalse();
    }

    @Test
    @DisplayName("an unread shape is no shape, and no shape is never gated")
    void anUnreadShapeIsNeverGated() {
        StageGate.Ruling ruling = StageGate.check(underway(TaskStage.PLANNING), null);

        assertThat(ruling.blocked()).isFalse();
        assertThat(ruling.asked()).isEqualTo(RequestShape.NONE);
    }

    @Test
    @DisplayName("a cleared turn still reports what it saw, so the page is not only told about refusals")
    void aClearedTurnStillReportsWhatItSaw() {
        StageGate.Ruling ruling =
                StageGate.check(underway(TaskStage.EXECUTION), RequestShape.EXECUTION);

        assertThat(ruling.asked()).isEqualTo(RequestShape.EXECUTION);
        assertThat(ruling.why()).isEmpty();
    }
}
