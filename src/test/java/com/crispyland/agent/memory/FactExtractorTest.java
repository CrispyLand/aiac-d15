package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactExtractorTest {

    private final ScriptedExtractor llm = new ScriptedExtractor();

    private FactExtractor extractor() {
        return new FactExtractor(llm, "extractor", 12, 600, "low");
    }

    @Test
    void aFactListIsParsedIntoOrderedKeyValuePairs() {
        llm.content = """
                name: Nur
                database: Postgres 16
                deadline: end of Q3""";

        Facts facts = extractor().update(Facts.EMPTY, "I'm Nur, Postgres 16, due end of Q3").orElseThrow();

        assertThat(facts.entries()).containsExactly(
                new Facts.Fact("name", "Nur"),
                new Facts.Fact("database", "Postgres 16"),
                new Facts.Fact("deadline", "end of Q3"));
        assertThat(facts.revision()).isEqualTo(1);
        assertThat(facts.buildTokens()).isEqualTo(90);
    }

    @Test
    void aChangedMindOverwritesTheKeyInsteadOfContradictingIt() {
        // The point of keying the memory. Prose notes would end up holding both statements and
        // leave the model to guess which one is current.
        Facts before = Facts.EMPTY.updatedWith(List.of(
                new Facts.Fact("database", "Postgres 16"),
                new Facts.Fact("name", "Nur")), 12, 90);
        llm.content = "database: Postgres 15";

        Facts after = extractor().update(before, "actually make it Postgres 15").orElseThrow();

        // Overwritten in place: the corrected fact keeps its position rather than jumping to the
        // end, so a block a human is reading does not reshuffle itself every time it is updated.
        assertThat(after.entries()).containsExactly(
                new Facts.Fact("database", "Postgres 15"),
                new Facts.Fact("name", "Nur"));
        assertThat(after.revision()).isEqualTo(2);
        // Both extraction calls are on the bill, not just the latest one.
        assertThat(after.buildTokens()).isEqualTo(180);
    }

    @Test
    void aStandingFactTheExtractionDidNotMentionIsKept() {
        // The bug this exists for: asked to re-emit eleven standing facts alongside a twelfth,
        // the model answered with the twelfth alone. Replacing the block on that reply lost the
        // other ten, and nothing about the shorter answer said so.
        Facts before = Facts.EMPTY.updatedWith(List.of(
                new Facts.Fact("name", "Nur"),
                new Facts.Fact("database", "Postgres 16")), 12, 90);
        llm.content = "budget: 300 dollars a month";

        Facts after = extractor().update(before, "the budget is 300 dollars a month").orElseThrow();

        assertThat(after.entries()).extracting(Facts.Fact::key)
                .containsExactly("name", "database", "budget");
    }

    @Test
    void theExtractorSeesTheCurrentBlockSoItCanRewriteRatherThanAppend() {
        Facts before = Facts.EMPTY.updatedWith(List.of(new Facts.Fact("database", "Postgres 16")), 12, 90);
        llm.content = "database: Postgres 15";

        extractor().update(before, "make it 15");

        assertThat(llm.last.messages().get(1).content())
                .contains("CURRENT FACTS:")
                .contains("- database: Postgres 16")
                .contains("NEW MESSAGE:")
                .contains("make it 15");
        assertThat(llm.last.temperature()).isZero();
        assertThat(llm.last.reasoningEffort()).isEqualTo("low");
    }

    @Test
    void theAssistantsOwnWordsAreNeverExtractedIntoFacts() {
        // Only the user's message is ever shown to the extractor. Promoting the model's prose
        // into "established fact" would launder a hallucination into something every later
        // turn is instructed to trust.
        llm.content = "database: Postgres 16";
        extractor().update(Facts.EMPTY, "use Postgres 16");

        assertThat(llm.last.messages()).hasSize(2);
        assertThat(llm.last.messages()).noneMatch(m -> "assistant".equals(m.role()));
    }

    @Test
    void bulletsAndNumberingAreToleratedRatherThanTreatedAsTotalMemoryLoss() {
        llm.content = """
                - name: Nur
                * database: Postgres 16
                3) deadline: end of Q3
                this line has no colon and is skipped""";

        Facts facts = extractor().update(Facts.EMPTY, "anything").orElseThrow();

        assertThat(facts.entries()).extracting(Facts.Fact::key)
                .containsExactly("name", "database", "deadline");
    }

    @Test
    void theBlockIsCappedSoItCannotBecomeTheThingItReplaces() {
        llm.content = """
                a: 1
                b: 2
                c: 3
                d: 4""";

        Facts facts = new FactExtractor(llm, "extractor", 2, 600, "low")
                .update(Facts.EMPTY, "anything").orElseThrow();

        assertThat(facts.entries()).extracting(Facts.Fact::key).containsExactly("a", "b");
    }

    @Test
    void aTruncatedListIsRefusedBecauseTheBlockIsReplacedWholesale() {
        // The gpt-oss trap again: hidden reasoning tokens are billed against the same ceiling
        // and spent first. Accepting this would drop every fact after the cut — silently,
        // because there is no message left behind to notice is missing.
        Facts before = Facts.EMPTY.updatedWith(List.of(
                new Facts.Fact("name", "Nur"),
                new Facts.Fact("database", "Postgres 16")), 12, 90);
        llm.content = "name: Nur";
        llm.finishReason = "length";

        assertThat(extractor().update(before, "and the deadline is Q3")).isEmpty();
    }

    @Test
    void proseInsteadOfFactsKeepsThePreviousBlockRatherThanWipingIt() {
        Facts before = Facts.EMPTY.updatedWith(List.of(new Facts.Fact("name", "Nur")), 12, 90);
        llm.content = "There is nothing new to record in this message.";

        assertThat(extractor().update(before, "hello again")).isEmpty();
    }

    @Test
    void anUnchangedBlockIsNotANewRevision() {
        // Still billed — but a revision counter that ticks on every turn stops meaning
        // "the memory moved" and starts meaning "a turn happened", which the transcript says.
        Facts before = Facts.EMPTY.updatedWith(List.of(new Facts.Fact("name", "Nur")), 12, 90);
        llm.content = "name: Nur";

        assertThat(extractor().update(before, "how are you?")).isEmpty();
    }

    @Test
    void anEmptyMessageIsNotWorthACall() {
        assertThat(extractor().update(Facts.EMPTY, "  ")).isEmpty();
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
