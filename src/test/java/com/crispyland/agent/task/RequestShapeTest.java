package com.crispyland.agent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of the turn that says what was asked for, as opposed to where the job is.
 * <p>
 * Everything here is about one decision: an unreadable value becomes {@link RequestShape#NONE}
 * rather than {@code null} or a nearest match. {@code NONE} is the answer that stops nothing, so a
 * model that garbles the line costs the user an ungated turn — annoying — where a guess would cost
 * them a blocked one for a reason nobody typed.
 */
class RequestShapeTest {

    @Test
    @DisplayName("the three shapes the model is offered come back as themselves")
    void theThreeShapesComeBackAsThemselves() {
        assertThat(RequestShape.from("planning")).isEqualTo(RequestShape.PLANNING);
        assertThat(RequestShape.from("execution")).isEqualTo(RequestShape.EXECUTION);
        assertThat(RequestShape.from("validation")).isEqualTo(RequestShape.VALIDATION);
        assertThat(RequestShape.from("none")).isEqualTo(RequestShape.NONE);
    }

    @Test
    @DisplayName("case and surrounding space are the model's habits, not a different answer")
    void caseAndSurroundingSpaceAreNotADifferentAnswer() {
        assertThat(RequestShape.from("  EXECUTION ")).isEqualTo(RequestShape.EXECUTION);
        assertThat(RequestShape.from("Validation")).isEqualTo(RequestShape.VALIDATION);
    }

    @Test
    @DisplayName("anything unrecognised asks for nothing, so nothing is stopped on its account")
    void anythingUnrecognisedAsksForNothing() {
        assertThat(RequestShape.from(null)).isEqualTo(RequestShape.NONE);
        assertThat(RequestShape.from("")).isEqualTo(RequestShape.NONE);
        assertThat(RequestShape.from("implementation")).isEqualTo(RequestShape.NONE);
        assertThat(RequestShape.from("exec")).isEqualTo(RequestShape.NONE);
    }

    @Test
    @DisplayName("done is a stage a person declares, never a thing a message can ask for")
    void doneIsNotAShape() {
        assertThat(RequestShape.from(TaskStage.DONE.id())).isEqualTo(RequestShape.NONE);
    }

    @Test
    @DisplayName("every shape that names a stage spells it the same way the stage does")
    void everyShapeThatNamesAStageSpellsItTheSameWay() {
        // The prompt lists both vocabularies. If they ever drift, the model is being asked to
        // answer in words that mean one thing on the way in and another on the way out.
        for (RequestShape shape : RequestShape.values()) {
            if (shape == RequestShape.NONE) {
                continue;
            }
            assertThat(TaskStage.from(shape.id())).isNotNull();
        }
    }

    @Test
    @DisplayName("read from the turn's stage fields, where the extractor puts it")
    void readFromTheTurnsStageFields() {
        assertThat(RequestShape.in(Map.of(TaskState.ASKS_FOR_FIELD, "execution")))
                .isEqualTo(RequestShape.EXECUTION);
    }

    @Test
    @DisplayName("no stage lines at all is not a request for anything")
    void noStageLinesAtAllIsNotARequestForAnything() {
        assertThat(RequestShape.in(null)).isEqualTo(RequestShape.NONE);
        assertThat(RequestShape.in(Map.of())).isEqualTo(RequestShape.NONE);
        assertThat(RequestShape.in(Map.of(TaskState.STAGE_FIELD, "execution")))
                .isEqualTo(RequestShape.NONE);
    }

    @Test
    @DisplayName("a stage move and a request are read separately and neither becomes the other")
    void aStageMoveAndARequestAreReadSeparately() {
        // The line that matters for the gate: a message asking for implementation must not thereby
        // propose moving into execution, or the input to the gate walks the task past it.
        Map<String, String> fields = new HashMap<>();
        fields.put(TaskState.ASKS_FOR_FIELD, "execution");
        fields.put(TaskState.STEP_FIELD, "settling the schema");

        assertThat(RequestShape.in(fields)).isEqualTo(RequestShape.EXECUTION);
        assertThat(TaskState.Proposal.from(fields).stage()).isNull();
    }
}
