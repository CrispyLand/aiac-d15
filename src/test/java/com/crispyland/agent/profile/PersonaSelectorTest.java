package com.crispyland.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Selection has four ways to arrive at a lens and they are tried in a fixed order. The order is
 * the contract, not an implementation detail: an explicit pick must beat a keyword, or the
 * dropdown on the page would be advisory.
 */
class PersonaSelectorTest {

    @TempDir
    Path root;

    private PersonaSelector selector;

    @BeforeEach
    void seed() throws IOException {
        write("users/russell.yml", """
                display-name: Russell
                language: Russian
                default-lens: economist""");
        write("users/dana.yml", """
                display-name: Dana
                language: English""");
        write("lenses/economist.yml", """
                title: Economist
                keywords: [инфляц, ставк]""");
        write("lenses/chemist.yml", """
                title: Chemist
                keywords: [химия, реакц]""");
        selector = new PersonaSelector(new Profiles(root));
    }

    @Test
    void anExplicitPickWinsOverAKeywordThatWouldHaveMatched() {
        // The message is about chemistry and the dropdown says economist. The dropdown wins:
        // it is the only one of the two that the user actually chose.
        PersonaSelector.Selection selection =
                selector.select("russell", "economist", "расскажи про химия реакц");

        assertThat(selection.lens().id()).isEqualTo("economist");
        assertThat(selection.why()).isEqualTo("picked");
    }

    @Test
    void aKeywordInTheMessagePicksTheLensAndSaysWhichWordDidIt() {
        PersonaSelector.Selection selection = selector.select("dana", null, "что такое реакц?");

        assertThat(selection.lens().id()).isEqualTo("chemist");
        // The reason is on the page, so "it refused that" becomes a report somebody can act on.
        assertThat(selection.why()).isEqualTo("matched 'реакц'");
    }

    @Test
    void withNothingToMatchOnTheUsersOwnDefaultApplies() {
        PersonaSelector.Selection selection = selector.select("russell", null, "привет");

        assertThat(selection.lens().id()).isEqualTo("economist");
        assertThat(selection.why()).isEqualTo("russell's default");
    }

    @Test
    void aUserWithNoDefaultAndNoMatchIsAnsweredWithNoLensAtAll() {
        PersonaSelector.Selection selection = selector.select("dana", null, "привет");

        assertThat(selection.persona().hasUser()).isTrue();
        assertThat(selection.persona().hasLens()).isFalse();
        assertThat(selection.why()).isEqualTo("no lens matched");
    }

    @Test
    void anUnknownUserIsAnsweredUnpersonalizedRatherThanRefused() {
        // A stale cookie pointing at a deleted profile must not be an error page. The visitor
        // loses their formatting, not their conversation.
        PersonaSelector.Selection selection = selector.select("ghost", null, "привет");

        assertThat(selection.persona().isPresent()).isFalse();
        assertThat(selection.persona().render()).isEmpty();
    }

    @Test
    void anUnknownDefaultLensFallsBackToNoLensInsteadOfFailing() throws IOException {
        write("users/stale.yml", "default-lens: astrologer");

        PersonaSelector.Selection selection = selector.select("stale", null, "привет");

        assertThat(selection.persona().hasUser()).isTrue();
        assertThat(selection.persona().hasLens()).isFalse();
    }

    @Test
    void aLensCanBeWornWithoutAUserProfile() {
        // The two halves are independent: a visitor who never picked a profile can still ask a
        // question that a lens claims.
        PersonaSelector.Selection selection = selector.select(null, null, "про инфляц");

        assertThat(selection.persona().hasUser()).isFalse();
        assertThat(selection.lens().id()).isEqualTo("economist");
    }

    @Test
    void nobodyAndNothingIsSelectionNONE() {
        assertThat(selector.select(null, null, "привет")).isEqualTo(PersonaSelector.Selection.NONE);
    }

    @Test
    void lensesAreTriedInListedOrderSoTheChoiceIsReproducible() {
        // Two lenses could claim this message. The first in the listing wins, every time —
        // otherwise the same question would get a different persona on different runs.
        PersonaSelector.Selection first = selector.select(null, null, "химия и инфляц");
        PersonaSelector.Selection again = selector.select(null, null, "химия и инфляц");

        assertThat(first.lens().id()).isEqualTo("chemist");
        assertThat(again.lens().id()).isEqualTo(first.lens().id());
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
