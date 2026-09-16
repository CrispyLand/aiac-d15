package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

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
