package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The routing table is the day's requirement — "explicitly choose what is saved where" — so these
 * tests are less about behaviour than about pinning the table down. Every one of them would still
 * pass if the model were replaced tomorrow, which is the point.
 */
class MemoryRouterTest {

    private final MemoryRouter router = new MemoryRouter();

    @Test
    void eachTagLandsInExactlyOneLayerAndNothingIsDecidedAtRuntime() {
        MemoryRouter.Routed routed = router.route(List.of(
                new MemoryRouter.Line("task", "database", "Postgres 16"),
                new MemoryRouter.Line("decision", "deadline", "end of Q3"),
                new MemoryRouter.Line("profile", "name", "Nur"),
                new MemoryRouter.Line("knowledge", "team", "four backend engineers")));

        assertThat(routed.working()).containsExactly(
                new Facts.Fact("database", "Postgres 16", false),
                new Facts.Fact("deadline", "end of Q3", true));
        assertThat(routed.longTerm()).containsExactly(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur"),
                new LongTermMemory.Entry(LongTermKind.KNOWLEDGE, "team", "four backend engineers"));
        assertThat(routed.dropped()).isEmpty();
    }

    @Test
    void aDecisionWaitsInWorkingMemoryInsteadOfGoingStraightToLongTerm() {
        // Load-bearing, not tidy-minded. Long-term is shared by every branch, so a decision
        // written there while the task is still open appears inside the forks that exist
        // precisely to disagree with it — and each fork then argues with a position it is
        // simultaneously told is settled.
        MemoryRouter.Routed routed = router.route(
                List.of(new MemoryRouter.Line("decision", "database", "Postgres 16")));

        assertThat(routed.longTerm()).isEmpty();
        assertThat(routed.working()).singleElement()
                .returns(true, Facts.Fact::settled);
    }

    @Test
    void aTaskLineIsScratchAndIsNotMarkedAgreed() {
        // The flag is the difference between "this is where we are" and "this is agreed". It
        // decides which lines survive the task closing, so defaulting it either way loses.
        MemoryRouter.Routed routed = router.route(
                List.of(new MemoryRouter.Line("task", "database", "Postgres 16")));

        assertThat(routed.working()).singleElement().returns(false, Facts.Fact::settled);
    }

    @Test
    void anUnrecognisedTagIsDroppedRatherThanGuessedAt() {
        // Refusing to store costs one turn of forgetfulness and the next message usually says it
        // again. Storing under the wrong lifetime is not self-correcting: the layer it landed in
        // is the layer that will still be replaying it in a month.
        MemoryRouter.Routed routed = router.route(List.of(
                new MemoryRouter.Line("personal", "name", "Nur"),
                new MemoryRouter.Line("task", "database", "Postgres 16")));

        assertThat(routed.dropped()).containsExactly(new MemoryRouter.Line("personal", "name", "Nur"));
        assertThat(routed.working()).hasSize(1);
        assertThat(routed.longTerm()).isEmpty();
    }

    @Test
    void tagsAreMatchedWithoutCaseOrSurroundingSpace() {
        MemoryRouter.Routed routed = router.route(
                List.of(new MemoryRouter.Line(" Profile ", "name", "Nur")));

        assertThat(routed.longTerm()).singleElement().returns(LongTermKind.PROFILE,
                LongTermMemory.Entry::kind);
    }

    @Test
    void noneIsARealAnswerAndIsNeitherStoredNorTreatedAsAFailure() {
        MemoryRouter.Routed routed = router.route(
                List.of(new MemoryRouter.Line("none", "", "nothing to keep")));

        assertThat(routed.isEmpty()).isTrue();
        assertThat(routed.dropped()).isEmpty();
    }

    @Test
    void nothingIsEverRoutedIntoTheTranscript() {
        // Short-term memory is written by the act of having the conversation. It is the one layer
        // with no extraction cost at all, and a tag pointing at it would be a line the agent pays
        // to invent and then pretends the user said.
        assertThat(MemoryTag.values())
                .noneMatch(tag -> tag.destination() == MemoryLayer.SHORT_TERM);
    }

    @Test
    void aStageLineIsPulledOutBeforeTheLayersAndLandsInNoneOfThem() {
        // Every other tag answers "what is known"; this one answers "where has the job got to".
        // Routing it into working memory would make it a fact the extractor can restate at will,
        // when the whole point is that it may only change by a move the transition table allows.
        MemoryRouter.Routed routed = router.route(List.of(
                new MemoryRouter.Line("stage", "stage", "execution"),
                new MemoryRouter.Line("stage", "next", "review the migration"),
                new MemoryRouter.Line("task", "database", "Postgres 16")));

        // Keyed, not ordered: the proposal is read field by field, so the order the extractor
        // happened to emit them in carries no meaning and is not pinned here.
        assertThat(routed.stage()).containsOnly(
                entry("stage", "execution"), entry("next", "review the migration"));
        assertThat(routed.working()).hasSize(1);
        assertThat(routed.longTerm()).isEmpty();
        assertThat(routed.dropped()).isEmpty();
    }

    @Test
    void aStageLineIsNotADroppedLineEvenThoughItHasNoDestination() {
        // The skip that drops an unrecognised tag and the skip that hands a stage line elsewhere
        // look identical from inside the loop. Conflating them would report every legitimate
        // transition as a routing failure.
        assertThat(MemoryTag.STAGE.destination()).isNull();
        assertThat(MemoryTag.STAGE.isTaskState()).isTrue();

        MemoryRouter.Routed routed = router.route(
                List.of(new MemoryRouter.Line("stage", "stage", "validation")));

        assertThat(routed.dropped()).isEmpty();
        assertThat(routed.isEmpty()).isFalse();
    }

    @Test
    void onlyTheStageTagReportsWhereTheJobIs() {
        // Pinned so a later tag cannot quietly acquire the ability to move the machine.
        assertThat(MemoryTag.values()).filteredOn(MemoryTag::isTaskState)
                .containsExactly(MemoryTag.STAGE);
    }

    @Test
    void anEmptyOrMissingExtractionRoutesToNothing() {
        assertThat(router.route(List.of())).isEqualTo(MemoryRouter.Routed.NOTHING);
        assertThat(router.route(null)).isEqualTo(MemoryRouter.Routed.NOTHING);
    }

    @Test
    void onlyTheTagsWhoseDestinationIsWorkingCanEverBePromotedLater() {
        // The one piece of routing that is not a straight lookup, pinned here so a new tag cannot
        // quietly acquire a promotion path by having a promoteAs set for a different reason.
        assertThat(MemoryTag.DECISION.heldUntilTaskCloses()).isTrue();
        assertThat(MemoryTag.TASK.heldUntilTaskCloses()).isFalse();
        assertThat(MemoryTag.PROFILE.heldUntilTaskCloses()).isFalse();
        assertThat(MemoryTag.KNOWLEDGE.heldUntilTaskCloses()).isFalse();
        assertThat(MemoryTag.NONE.heldUntilTaskCloses()).isFalse();
    }
}
