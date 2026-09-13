package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HistoryCompressorTest {

    private final ScriptedSummarizer summarizer = new ScriptedSummarizer();

    /** Keep the last 4 messages; fold once 6 more have accumulated behind them. */
    private HistoryCompressor compressor() {
        return new HistoryCompressor(summarizer, new BpeTokenCounter(), "small", 4, 6, 200);
    }

    @Test
    void aShortDialogueIsLeftAloneAndCostsNothing() {
        assertThat(compressor().compact(Summary.EMPTY, history(8))).isEmpty();
        assertThat(summarizer.calls).isZero();
    }

    @Test
    void theRecentTailIsKeptVerbatimAndOnlyTheBacklogIsFolded() {
        Optional<HistoryCompressor.Compaction> compaction = compressor().compact(Summary.EMPTY, history(12));

        assertThat(compaction).isPresent();
        assertThat(compaction.get().foldedMessages()).isEqualTo(8);
        // The last four messages were never shown to the summarizer — recency is where
        // pronouns and corrections live, and notes are a poor substitute for them.
        assertThat(summarizer.brief).contains("reply 7").doesNotContain("message 8");
    }

    @Test
    void theFoldStopsOnATurnBoundarySoTheWindowOpensOnAUserMessage() {
        // 11 messages, keeping 4, would fold 7 — which would leave the retained history
        // starting on an assistant reply, reading as though the model spoke first.
        Optional<HistoryCompressor.Compaction> compaction = compressor().compact(Summary.EMPTY, history(11));

        assertThat(compaction).isPresent();
        assertThat(compaction.get().foldedMessages()).isEqualTo(6);
        assertThat(history(11).get(6).isUser()).isTrue();
    }

    @Test
    void eachPassRewritesTheNotesRatherThanAppendingToThem() {
        HistoryCompressor compressor = compressor();
        Summary first = compressor.compact(Summary.EMPTY, history(12)).orElseThrow().summary();
        Summary second = compressor.compact(first, history(12)).orElseThrow().summary();

        // The previous notes go back in as input, so nothing older is lost...
        assertThat(summarizer.brief).contains(first.text());
        // ...but what comes out replaces them, which is the only reason this part of the
        // prompt stays bounded instead of growing a second time.
        assertThat(second.text()).isEqualTo("notes 2").doesNotContain("notes 1");
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.coveredMessages()).isEqualTo(16);
    }

    @Test
    void theRunningTotalsRecordBothWhatWasSavedAndWhatTheSavingCost() {
        Summary summary = compressor().compact(Summary.EMPTY, history(12)).orElseThrow().summary();

        assertThat(summary.replacedTokens()).isPositive();
        assertThat(summary.buildTokens()).isEqualTo(90);
    }

    @Test
    void emptyNotesAreRejectedRatherThanLettingTheMessagesBeDropped() {
        summarizer.content = "   ";

        assertThat(compressor().compact(Summary.EMPTY, history(12))).isEmpty();
    }

    @Test
    void notesTruncatedByTheTokenCeilingAreRejectedToo() {
        // Seen live: gpt-oss spent the whole 200-token budget on hidden reasoning and emitted
        // "- Nur," before being cut off. Folding the messages behind half a sentence loses
        // exactly what compression exists to preserve, so a truncated pass is no pass at all.
        summarizer.content = "- Nur,";
        summarizer.finishReason = "length";

        assertThat(compressor().compact(Summary.EMPTY, history(12))).isEmpty();
    }

    @Test
    void theSummarizerIsAskedToThinkLessSoTheCeilingIsSpentOnNotes() {
        new HistoryCompressor(summarizer, new BpeTokenCounter(), "small", 4, 6, 200, "low")
                .compact(Summary.EMPTY, history(12));

        assertThat(summarizer.reasoningEffort).isEqualTo("low");
        // Blank has to mean "omit": qwen and compound reject the parameter outright.
        new HistoryCompressor(summarizer, new BpeTokenCounter(), "small", 4, 6, 200, "  ")
                .compact(Summary.EMPTY, history(12));
        assertThat(summarizer.reasoningEffort).isNull();
    }

    private static List<Message> history(int count) {
        List<Message> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(i % 2 == 0 ? Message.user("message " + i) : Message.assistant("reply " + i));
        }
        return messages;
    }

    private static final class ScriptedSummarizer implements LlmClient {
        private String brief;
        private String content;
        private String reasoningEffort;
        private String finishReason = "stop";
        private int calls;

        @Override
        public ChatResponse complete(ChatRequest request) {
            brief = request.messages().get(1).content();
            reasoningEffort = request.reasoningEffort();
            calls++;
            return new ChatResponse(content == null ? "notes " + calls : content,
                    request.model(), finishReason, new TokenUsage(60, 30, 90));
        }
    }
}
