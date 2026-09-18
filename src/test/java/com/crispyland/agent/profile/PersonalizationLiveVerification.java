package com.crispyland.agent.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.Agent;
import com.crispyland.agent.AgentResult;
import com.crispyland.agent.memory.MemoryScope;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The claim this feature makes is "the same question, asked by two people, comes back written two
 * different ways, and nobody had to say so in the message". That claim is about a real model's
 * output, so no amount of unit testing settles it — this asks the actual provider and prints both
 * answers next to each other.
 * <p>
 * Skipped unless {@code GROQ_API_KEY} is set, so it costs nothing in an ordinary build and nothing
 * in CI. Run it on purpose:
 * <pre>
 * GROQ_API_KEY=... mvn -o test -Dtest=PersonalizationLiveVerification
 * </pre>
 * <p>
 * Every prompt gets a fresh conversation id. Sharing one would let the first answer's phrasing
 * leak into the second through the transcript, and then the difference on screen would be the
 * dialogue's doing rather than the profile's — which is the one thing this is trying to isolate.
 * Stores are forced in-memory for the same reason, and so a verification run never leaves anything
 * behind in {@code ./data}.
 */
@SpringBootTest(properties = "agent.memory.store=memory")
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class PersonalizationLiveVerification {

    /**
     * Deliberately neutral. Not one of them says "briefly", "in Russian" or "as an economist" —
     * if the prompt asked for the formatting, the profile would not have to be working for the
     * output to look right.
     */
    private static final List<String> PROMPTS = List.of(
            "Стоит ли повышать ключевую ставку, если инфляция ускоряется?",
            "Я выгораю на работе. Что делать?",
            "Что такое хорошая архитектура приложения?");

    private static final java.time.Duration PACE = java.time.Duration.ofSeconds(12);

    @Autowired
    private Agent agent;

    @Autowired
    private Profiles profiles;

    @Autowired
    private PersonaSelector personas;

    @Test
    void theSameQuestionAnsweredForEachProfileSideBySide() {
        List<UserProfile> users = profiles.users();
        assertThat(users).as("the seeded profiles directory").isNotEmpty();

        for (String prompt : PROMPTS) {
            System.out.println("\n" + "=".repeat(100));
            System.out.println("Q: " + prompt);
            System.out.println("=".repeat(100));

            List<String> answers = new ArrayList<>();
            for (UserProfile user : users) {
                answers.add(askAs(user.id(), null, prompt));
            }

            // Two profiles that differ in language, tone, shape and length cannot honestly produce
            // the same string. If they do, the block is not reaching the model.
            assertThat(answers).doesNotHaveDuplicates();
        }
    }

    @Test
    void theSameProfileUnderTwoLensesAnswersFromTwoDifferentRoles() {
        // The other axis. The person is constant — same language, same tone, same length — and
        // only the role moves, so whatever changes is the lens and nothing else.
        String prompt = "Почему цены растут?";
        System.out.println("\n" + "=".repeat(100));
        System.out.println("Q (russell, lens by lens): " + prompt);
        System.out.println("=".repeat(100));

        List<String> answers = new ArrayList<>();
        for (Lens lens : profiles.lenses()) {
            answers.add(askAs("russell", lens.id(), prompt));
        }

        assertThat(answers).doesNotHaveDuplicates();
    }

    @Test
    void theLensIsChosenFromTheMessageWithNothingPicked() {
        // "Applied automatically" means this: nobody touched the dropdown and the economist
        // answered anyway, because the question contained a word the economist claims.
        PersonaSelector.Selection economics =
                personas.select("dana", null, "как инфляц влияет на зарплаты?");
        PersonaSelector.Selection chemistry =
                personas.select("dana", null, "какая реакц идёт при горении?");

        System.out.printf("%n  '...инфляц...' -> %s (%s)%n  '...реакц...'  -> %s (%s)%n",
                economics.persona().label(), economics.why(),
                chemistry.persona().label(), chemistry.why());

        assertThat(economics.lens().id()).isNotEqualTo(chemistry.lens().id());
    }

    /**
     * One real call, printed with everything that decided how it was made.
     * <p>
     * Paced, because a turn is two calls — the answer and the fact extraction behind it — and the
     * free tier allows 8,000 tokens a minute. Fired back to back this matrix hits 429 about
     * two-thirds of the way through, and a rate limit is not a result: it fails the run without
     * telling you anything about personalization. Waiting is the honest fix here; a retry loop
     * would be one, in production code, for a problem this test does not have.
     */
    private String askAs(String userId, String lensId, String prompt) {
        pace();
        PersonaSelector.Selection selection = personas.select(userId, lensId, prompt);
        // A fresh id per call: no transcript, no working memory, no long-term — the profile is
        // then the only thing in the prompt that is not the question itself.
        MemoryScope scope = MemoryScope.of("verify-" + userId + "-" + System.nanoTime());

        AgentResult result = agent.handle(scope, selection.persona(), prompt, null);

        System.out.println("\n--- " + selection.persona().label()
                + "  (" + selection.why() + ")"
                + "  max_tokens=" + result.effectiveConfig().maxCompletionTokens()
                + "  temp=" + result.effectiveConfig().temperature()
                + "  profile=" + result.budget().profileTokens() + " tok");
        System.out.println(result.answer());
        return result.answer();
    }

    /** Roughly one turn's worth of the per-minute token allowance. */
    private static void pace() {
        try {
            Thread.sleep(PACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing", e);
        }
    }
}
