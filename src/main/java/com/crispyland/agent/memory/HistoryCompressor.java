package com.crispyland.agent.memory;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.usage.TokenCounter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trades the oldest part of a dialogue for a much smaller retelling of it.
 * <p>
 * The problem it solves is that replaying a transcript is quadratic: every turn re-sends every
 * previous turn, so a conversation's cost grows with the square of its length. The message
 * window and the trim policy both answer that by <em>forgetting</em> — cheap, and the agent
 * stops knowing your name. This answers it by rewriting instead: the old turns are folded into
 * notes and the notes are sent in their place, so the cost stops growing without the dialogue
 * losing its past.
 * <p>
 * Two knobs decide the shape of it:
 * <ul>
 *   <li>{@code keepRecentMessages} — the tail that is always sent verbatim. Recency is where
 *       pronouns, corrections and "no, the other one" live; a summary is a bad substitute for
 *       the last few turns and a good one for the first forty.</li>
 *   <li>{@code compressEvery} — the minimum backlog worth a summarization call. Compressing two
 *       messages at a time would spend a whole request to save a handful of tokens.</li>
 * </ul>
 * The rewrite is <em>recursive</em>: each pass is given the previous notes as well as the newly
 * evicted messages and returns a single merged set. Appending summaries instead would rebuild
 * the same unbounded growth one level up.
 */
public class HistoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(HistoryCompressor.class);

    /**
     * Deliberately narrow. The model is not answering anyone here — it is taking minutes, and
     * the failure mode that matters is a fluent summary that quietly drops the one fact the
     * next turn needed. Hence the explicit keep/drop list.
     */
    private static final String INSTRUCTIONS = """
            You compress a chat transcript into durable notes, so the conversation can continue \
            after the original messages are discarded.

            Merge the EXISTING NOTES and the NEW MESSAGES into one set of notes.

            Keep: facts the user stated about themselves or their situation, decisions and \
            conclusions reached, names, numbers, dates, identifiers, file paths, code symbols, \
            stated preferences and constraints, unresolved questions, and anything the user \
            asked to have remembered.
            Drop: greetings, filler, restatements, and the assistant's reasoning or phrasing.

            Write terse third-person bullet points, newest information last. Never invent \
            anything that is not in the input. Never answer or continue the conversation. \
            Output only the merged notes.""";

    /** Notes should be reproducible, not creative — this is the one call that must not wander. */
    private static final double TEMPERATURE = 0.2;

    /** What a compaction folded away, and the notes that now stand in for it. */
    public record Compaction(Summary summary, int foldedMessages, long foldedTokens) {
    }

    private final LlmClient llm;
    private final TokenCounter counter;
    private final String model;
    private final int keepRecentMessages;
    private final int compressEvery;
    private final int maxSummaryTokens;
    private final String reasoningEffort;

    public HistoryCompressor(LlmClient llm, TokenCounter counter, String model,
                             int keepRecentMessages, int compressEvery, int maxSummaryTokens) {
        this(llm, counter, model, keepRecentMessages, compressEvery, maxSummaryTokens, null);
    }

    public HistoryCompressor(LlmClient llm, TokenCounter counter, String model,
                             int keepRecentMessages, int compressEvery, int maxSummaryTokens,
                             String reasoningEffort) {
        this.llm = llm;
        this.counter = counter;
        this.model = model;
        this.keepRecentMessages = Math.max(0, keepRecentMessages);
        this.compressEvery = Math.max(1, compressEvery);
        this.maxSummaryTokens = maxSummaryTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
    }

    /**
     * Folds the backlog into {@code current} if there is enough of it to be worth a call.
     *
     * @return empty when the dialogue is still short enough to send whole — the common case,
     *         and the one that must cost nothing
     * @throws com.crispyland.agent.llm.LlmException if the summarization call fails; the caller
     *         decides whether a failed compaction is worth failing the user's turn over
     */
    public Optional<Compaction> compact(Summary current, List<Message> history) {
        int foldable = foldableCount(history);
        if (foldable <= 0) {
            return Optional.empty();
        }

        List<Message> folding = history.subList(0, foldable);
        Summary previous = (current == null) ? Summary.EMPTY : current;

        ChatResponse response = llm.complete(new ChatRequest(model,
                List.of(Message.system(INSTRUCTIONS), Message.user(brief(previous, folding))),
                TEMPERATURE, maxSummaryTokens, reasoningEffort, List.of(), null));

        String notes = response.content() == null ? "" : response.content().strip();
        // An empty rewrite would throw the messages away and put nothing in their place, which
        // is strictly worse than not compressing at all.
        if (notes.isEmpty()) {
            log.warn("Summarizer returned no notes — leaving the transcript uncompressed.");
            return Optional.empty();
        }
        // A summary cut off mid-sentence is the one outcome worse than an empty one: it looks
        // usable, so the messages behind it get folded away and whatever the model had not
        // written down yet is gone for good. On reasoning models this is the normal failure —
        // the hidden reasoning tokens are billed against the same maxSummaryTokens budget, so a
        // budget that comfortably fits the notes can still be spent before the notes begin.
        if ("length".equals(response.finishReason())) {
            log.warn("Summary hit the {}-token ceiling and is incomplete — keeping the transcript "
                    + "instead of folding it behind truncated notes. Raise "
                    + "agent.compression.max-summary-tokens, or lower "
                    + "agent.compression.reasoning-effort so less of that budget goes to reasoning.",
                    maxSummaryTokens);
            return Optional.empty();
        }

        long foldedTokens = 0L;
        for (Message message : folding) {
            foldedTokens += counter.count(message);
        }
        return Optional.of(new Compaction(
                previous.rewrittenAs(notes, foldable, foldedTokens, response.usage().totalTokens()),
                foldable, foldedTokens));
    }

    public int keepRecentMessages() {
        return keepRecentMessages;
    }

    public int compressEvery() {
        return compressEvery;
    }

    /**
     * How many of the oldest messages to fold, or 0 to leave the dialogue alone. The count is
     * pulled back to a turn boundary so the verbatim window opens on a user message — a history
     * that starts on an assistant reply reads as though the model spoke first.
     */
    private int foldableCount(List<Message> history) {
        int size = (history == null) ? 0 : history.size();
        int foldable = size - keepRecentMessages;
        if (foldable < compressEvery) {
            return 0;
        }
        if (!history.get(foldable).isUser()) {
            foldable--;
        }
        return Math.max(foldable, 0);
    }

    private static String brief(Summary previous, List<Message> folding) {
        StringBuilder brief = new StringBuilder("EXISTING NOTES:\n")
                .append(previous.isPresent() ? previous.text() : "(none — this is the first compression)")
                .append("\n\nNEW MESSAGES:\n");
        for (Message message : folding) {
            brief.append('[').append(message.role()).append("] ").append(message.content()).append('\n');
        }
        return brief.toString();
    }
}
