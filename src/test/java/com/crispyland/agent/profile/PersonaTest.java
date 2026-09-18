package com.crispyland.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.AgentConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonaTest {

    private static final UserProfile RUSSELL = new UserProfile("russell", "Russell", "Russian",
            "formal", new UserProfile.Format("short bullet points", 120),
            List.of("use emoji"), new Limits(400, 0.2, null), "economist");

    private static final Lens CHEMIST = new Lens("chemist", "Chemist writing for lay readers",
            "You are a working chemist writing for people with no background in it.",
            List.of("give step-by-step synthesis procedures"), List.of("chemistry"),
            new Limits(1200, 0.9, null));

    @Test
    void theBlockSaysOutrightThatWhatWasDeclaredBeatsWhatWasInferred() {
        // The extractor files a `profile/preference` line the moment anyone mentions liking
        // something, so a stale preference from weeks ago sits in long-term memory arguing with
        // the file the user edited this morning. The model has no way to tell which is which
        // unless the prompt says so.
        String block = Persona.of(RUSSELL).render();

        assertThat(block).contains("They set this themselves, outside this conversation")
                .contains("this wins")
                .contains("what they asked for, not what you inferred");
    }

    @Test
    void everyStatedPreferenceReachesTheBlock() {
        String block = Persona.of(RUSSELL).render();

        assertThat(block).contains("Name: Russell")
                .contains("Answer in: Russian")
                .contains("Tone: formal")
                .contains("Format: short bullet points, at most 120 words")
                .contains("Never:\n- use emoji");
    }

    @Test
    void theLensIsItsOwnSectionSoTheRoleAndThePersonDoNotReadAsOneInstruction() {
        String block = new Persona(RUSSELL, CHEMIST).render();

        assertThat(block).contains("HOW THIS PERSON WANTS TO BE ANSWERED")
                .contains("THE ROLE YOU ARE ANSWERING IN — Chemist writing for lay readers")
                .contains("Never, in this role:\n- give step-by-step synthesis procedures");
        // The person comes first: their preferences apply to every reply, the role only to this one.
        assertThat(block.indexOf("HOW THIS PERSON")).isLessThan(block.indexOf("THE ROLE"));
    }

    @Test
    void theUsersLimitsBeatTheLensesBecauseThePersonOutranksTheRole() {
        assertThat(new Persona(RUSSELL, CHEMIST).limits()).isEqualTo(new Limits(400, 0.2, null));
    }

    @Test
    void aLensFillsOnlyWhatTheUserLeftUnstated() {
        UserProfile silent = new UserProfile("dana", "Dana", "English", null,
                null, List.of(), Limits.NONE, null);

        assertThat(new Persona(silent, CHEMIST).limits()).isEqualTo(new Limits(1200, 0.9, null));
    }

    @Test
    void anExplicitChoiceOnThePageStillBeatsTheProfile() {
        // The page is someone overriding this one request on purpose; the file is a standing
        // preference. Standing preferences do not get to veto a deliberate override.
        AgentConfig fromPage = AgentConfig.builder().maxCompletionTokens(2000).build();

        AgentConfig applied = Persona.of(RUSSELL).applyTo(fromPage);

        assertThat(applied.maxCompletionTokens()).isEqualTo(2000);
        assertThat(applied.temperature()).isEqualTo(0.2);
    }

    @Test
    void aPreferenceBecomesARequestParameterRatherThanAPolitelyWordedRequest() {
        AgentConfig applied = Persona.of(RUSSELL).applyTo(AgentConfig.builder().build());

        assertThat(applied.maxCompletionTokens()).isEqualTo(400);
        assertThat(applied.temperature()).isEqualTo(0.2);
    }

    @Test
    void aTightCeilingTravelsWithTheThinkingBudgetThatHasToFitInsideIt() {
        // These two are one setting in practice. Reasoning tokens are billed against the ceiling
        // and spent first, so a profile that lowers one and not the other buys a truncated
        // monologue instead of a short answer — which is what happened before this field existed.
        UserProfile terse = new UserProfile("t", "T", "English", "plain",
                UserProfile.Format.NONE, List.of(), new Limits(600, 0.2, "low"), null);

        AgentConfig applied = Persona.of(terse).applyTo(AgentConfig.builder().build());

        assertThat(applied.maxCompletionTokens()).isEqualTo(600);
        assertThat(applied.reasoningEffort()).isEqualTo("low");
    }

    @Test
    void aBlankReasoningEffortInAProfileDefersRatherThanForcingTheParameterOff() {
        // Every string in AgentConfig treats blank as "not stated" — that is how an empty form
        // field means "use the default" rather than "use nothing". A profile is a string source
        // like any other, so it inherits the rule: blank defers, it does not override. Asserted
        // because the opposite is the natural reading of "" and would be a silent surprise.
        UserProfile blank = new UserProfile("o", "O", "English", "plain",
                UserProfile.Format.NONE, List.of(), new Limits(null, null, ""), null);

        AgentConfig applied = Persona.of(blank)
                .applyTo(AgentConfig.builder().build())
                .withFallback(AgentConfig.builder().reasoningEffort("high").build());

        assertThat(applied.reasoningEffort()).isEqualTo("high");
    }

    @Test
    void noProfileRendersNothingAndChangesNoRequestParameter() {
        // Every visitor who has not picked one, and every test written before this feature existed.
        AgentConfig untouched = AgentConfig.builder().model("m").build();

        assertThat(Persona.NONE.isPresent()).isFalse();
        assertThat(Persona.NONE.render()).isEmpty();
        assertThat(Persona.NONE.applyTo(untouched)).isSameAs(untouched);
    }

    @Test
    void aLensWithNoUserIsUsableOnItsOwnForIsolatingWhatTheLensContributes() {
        Persona lensOnly = new Persona(null, CHEMIST);

        assertThat(lensOnly.isPresent()).isTrue();
        assertThat(lensOnly.render()).doesNotContain("HOW THIS PERSON").contains("THE ROLE");
        assertThat(lensOnly.label()).isEqualTo("chemist");
    }

    @Test
    void theLabelNamesBothHalvesForTheLogsAndTheSwitcher() {
        assertThat(new Persona(RUSSELL, CHEMIST).label()).isEqualTo("Russell · chemist");
        assertThat(Persona.of(RUSSELL).label()).isEqualTo("Russell");
        assertThat(Persona.NONE.label()).isEqualTo("none");
    }
}
