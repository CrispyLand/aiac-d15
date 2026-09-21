package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.task.RequestShape;
import com.crispyland.agent.task.TaskState;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MemoryExtractorTest {

    private final ScriptedExtractor llm = new ScriptedExtractor();

    /** Most of these test the parser, which has no interest in what is already held. */
    private static final List<String> NO_KEYS = List.of();

    private MemoryExtractor extractor() {
        return new MemoryExtractor(llm, "extractor", 12, 600, "low");
    }

    @Test
    void eachLineKeepsTheTagTheModelGaveItAndNothingMore() {
        llm.content = """
                profile/name: Nur
                task/database: Postgres 16
                decision/deadline: end of Q3""";

        MemoryExtractor.Extraction extraction = extractor().extract("I'm Nur, Postgres 16, due end of Q3", NO_KEYS, TaskState.EMPTY);

        assertThat(extraction.lines()).containsExactly(
                new MemoryRouter.Line("profile", "name", "Nur"),
                new MemoryRouter.Line("task", "database", "Postgres 16"),
                new MemoryRouter.Line("decision", "deadline", "end of Q3"));
        assertThat(extraction.costTokens()).isEqualTo(90);
    }

    @Test
    void theTagMenuIsGeneratedFromTheRoutingTableSoTheTwoCannotDrift() {
        // A prompt offering a tag the router has never heard of produces lines that are
        // extracted, paid for, and then dropped — with nothing but a WARN to say why.
        llm.content = "task/x: 1";
        extractor().extract("anything", NO_KEYS, TaskState.EMPTY);

        String instructions = llm.last.messages().getFirst().content();
        for (MemoryTag tag : MemoryTag.values()) {
            assertThat(instructions).contains("- " + tag.id() + " — " + tag.what());
        }
    }

    @Test
    void theRequestShapeVocabularyIsGeneratedTooSoTheGateCannotBeDisarmedByAWordItDoesNotKnow() {
        // The gate reads `asks-for` and reads anything it does not recognise as "nothing asked
        // for". A prompt offering a word the enum has never heard of therefore does not fail
        // loudly — it silently opens the gate on every turn that uses it.
        llm.content = "task/x: 1";
        extractor().extract("anything", NO_KEYS, TaskState.EMPTY);

        String instructions = llm.last.messages().getFirst().content();
        assertThat(instructions).contains("stage/asks-for");
        for (RequestShape shape : RequestShape.values()) {
            assertThat(instructions).contains("`" + shape.id() + "` — " + shape.what());
        }
    }

    @Test
    void theShapeOfTheRequestIsReadSeparatelyFromAnyMoveItProposes() {
        // The two travel on the same line prefix and mean unrelated things. If `asks-for` also
        // built the proposal, asking for implementation would move the task into execution, and
        // the gate's own input would walk the task straight past the gate.
        llm.content = "stage/asks-for: execution";

        Map<String, String> stage = new MemoryRouter()
                .route(extractor().extract("just write it", NO_KEYS, TaskState.EMPTY).lines())
                .stage();

        assertThat(RequestShape.in(stage)).isEqualTo(RequestShape.EXECUTION);
        assertThat(TaskState.Proposal.from(stage).isEmpty()).isTrue();
    }

    @Test
    void withNothingHeldYetTheExtractorSeesTheMessageAndNoEmptyHeading() {
        llm.content = "task/database: Postgres 15";

        extractor().extract("make it 15", NO_KEYS, TaskState.EMPTY);

        assertThat(llm.last.messages()).hasSize(2);
        assertThat(llm.last.messages().get(1).content()).isEqualTo("NEW MESSAGE:\nmake it 15");
        assertThat(llm.last.temperature()).isZero();
        assertThat(llm.last.reasoningEffort()).isEqualTo("low");
    }

    @Test
    void theKeysAlreadyInUseAreShownButTheirValuesAreNot() {
        // Keys are the minimum that makes an upsert actually update — without them gpt-oss
        // answered "Postgres 16 it is, final" with `postgres: 16` while `database: Postgres 16`
        // was already held, and two keys for one subject can both be true with one of them stale.
        //
        // The values stay out, and that restraint is the whole safety margin. Shown the values,
        // the model eventually re-emits standing facts, and on the turn it re-emits only some of
        // them a memory that trusts the reply loses the rest. A list of keys has nothing to
        // re-emit.
        llm.content = "task/database: Postgres 15";

        extractor().extract("make it 15", List.of("database", "name"), TaskState.EMPTY);

        String brief = llm.last.messages().get(1).content();
        assertThat(brief).isEqualTo("KEYS IN USE:\ndatabase, name\n\nNEW MESSAGE:\nmake it 15");
        assertThat(brief).doesNotContain("Postgres 16");
    }

    @Test
    void theAssistantsOwnWordsAreNeverExtracted() {
        // Only the user's message is ever shown to the extractor. Promoting the model's prose
        // into remembered fact would launder a hallucination into something every later turn is
        // instructed to trust.
        llm.content = "task/database: Postgres 16";
        extractor().extract("use Postgres 16", NO_KEYS, TaskState.EMPTY);

        assertThat(llm.last.messages()).noneMatch(m -> "assistant".equals(m.role()));
    }

    @Test
    void bulletsNumberingAndAColonForTheSlashAreAllTolerated() {
        // The instructions ask for none of this. A parser that accepts only the happy path turns
        // a cosmetic deviation into total memory loss for the turn.
        llm.content = """
                - profile/name: Nur
                * task/database: Postgres 16
                3) decision: deadline: end of Q3
                this line has no colon and is skipped""";

        assertThat(extractor().extract("anything", NO_KEYS, TaskState.EMPTY).lines()).containsExactly(
                new MemoryRouter.Line("profile", "name", "Nur"),
                new MemoryRouter.Line("task", "database", "Postgres 16"),
                new MemoryRouter.Line("decision", "deadline", "end of Q3"));
    }

    @Test
    void keysAreLowercasedSoTheSameSubjectAlwaysLandsOnTheSameKey() {
        // Two spellings of one subject defeat the upsert: both can be held at once and only one
        // of them is current.
        llm.content = "task/Database: Postgres 16";

        assertThat(extractor().extract("anything", NO_KEYS, TaskState.EMPTY).lines())
                .containsExactly(new MemoryRouter.Line("task", "database", "Postgres 16"));
    }

    @Test
    void anUnrecognisedTagIsPassedThroughForTheRouterToDropRatherThanFixedHere() {
        // One place decides what a tag means. Resolving it here as well would let "the model used
        // a tag we do not have" happen silently in two different files.
        llm.content = "personal/name: Nur";

        assertThat(extractor().extract("anything", NO_KEYS, TaskState.EMPTY).lines())
                .containsExactly(new MemoryRouter.Line("personal", "name", "Nur"));
    }

    @Test
    void aDeliberateSilenceIsKeptAsAnAnswerRatherThanInferredFromAnEmptyReply() {
        llm.content = "none: nothing to keep";

        assertThat(extractor().extract("how are you?", NO_KEYS, TaskState.EMPTY).lines())
                .containsExactly(new MemoryRouter.Line("none", "", "nothing to keep"));
    }

    @Test
    void aLineWithNoValueIsDroppedBecauseItWouldOverwriteARealOneWithNothing() {
        llm.content = """
                task/database:
                task/budget: 300 a month""";

        assertThat(extractor().extract("anything", NO_KEYS, TaskState.EMPTY).lines())
                .containsExactly(new MemoryRouter.Line("task", "budget", "300 a month"));
    }

    @Test
    void theReplyIsCappedSoOneTurnCannotFillTheBlockItFeeds() {
        llm.content = """
                task/a: 1
                task/b: 2
                task/c: 3
                task/d: 4""";

        MemoryExtractor.Extraction extraction =
                new MemoryExtractor(llm, "extractor", 2, 600, "low").extract("anything", NO_KEYS, TaskState.EMPTY);

        assertThat(extraction.lines()).extracting(MemoryRouter.Line::key).containsExactly("a", "b");
    }

    @Test
    void aTruncatedReplyIsRefusedBecauseTheCutLineParsesAsHalfAFact() {
        // The gpt-oss trap: hidden reasoning tokens are billed against the same ceiling and spent
        // first. A line cut in the middle still has a tag and a key, so it would be filed — and
        // then outrank the transcript on every later turn.
        llm.content = "profile/name: Nu";
        llm.finishReason = "length";

        assertThat(extractor().extract("I'm Nur and the deadline is Q3", NO_KEYS, TaskState.EMPTY)).isEqualTo(
                MemoryExtractor.Extraction.NOTHING);
    }

    @Test
    void proseInsteadOfTaggedLinesKeepsNothingRatherThanGuessingAtIt() {
        llm.content = "There is nothing new to record in this message.";

        assertThat(extractor().extract("hello again", NO_KEYS, TaskState.EMPTY).isEmpty()).isTrue();
    }

    @Test
    void anEmptyMessageIsNotWorthACall() {
        assertThat(extractor().extract("  ", NO_KEYS, TaskState.EMPTY).isEmpty()).isTrue();
        assertThat(llm.calls).isZero();
    }

    /** Returns whatever the test scripted, and records what it was asked. */
    private static final class ScriptedExtractor implements LlmClient {

        private String content = "";
        private String finishReason = "stop";
        private ChatRequest last;
        private int calls;

        @Override
        public ChatResponse complete(ChatRequest request) {
            last = request;
            calls++;
            return new ChatResponse(content, request.model(), finishReason, new TokenUsage(60, 30, 90));
        }
    }
}
