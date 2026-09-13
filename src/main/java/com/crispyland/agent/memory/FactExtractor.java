package com.crispyland.agent.memory;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a small key/value block of what the conversation has settled, refreshed after every
 * user message.
 * <p>
 * This is the summary's opposite number. Both replace an unbounded past with a bounded blob,
 * but a summary is prose and therefore <em>additive</em> — new notes are merged into old ones
 * and a changed mind reads as two statements that disagree. A keyed block is <em>replacive</em>:
 * a later "actually, make it Postgres 15" lands on the same {@code database} key and overwrites
 * it, so the block can never contradict itself. That is the whole reason to prefer it when the
 * dialogue is a negotiation — collecting a spec, agreeing requirements — rather than a story.
 * <p>
 * The model is shown the current block and asked only for what the new message adds or changes;
 * the reply is applied on top rather than swapped in. Asking instead for the complete updated
 * block — which this did at first — puts the whole memory on every single call: on the ninth
 * turn of the spec scenario gpt-oss answered a new requirement with that requirement alone, and
 * eleven standing facts went with it. Re-emitting a block the caller already has is also the
 * most expensive way to say "nothing else changed".
 * <p>
 * Extracted from the user's message only, never from the assistant's reply. A reply is the
 * model's own prose, and promoting it into "established fact" would launder a hallucination
 * into something every later turn is told to trust.
 */
public class FactExtractor {

    private static final Logger log = LoggerFactory.getLogger(FactExtractor.class);

    /**
     * The instruction that does the real work is "keep keys short, lowercase and stable": two
     * spellings of the same subject defeat the entire point, because {@code db} and
     * {@code database} can both be true at once and only one of them is current.
     */
    private static final String INSTRUCTIONS = """
            You maintain a compact key/value memory for an ongoing conversation.

            You are given the CURRENT FACTS and the user's NEW MESSAGE. Return ONLY the facts \
            the new message adds or changes. Never repeat a fact that already stands unchanged — \
            the current ones are kept automatically.

            Record durable specifics: the user's goal, hard requirements and constraints, \
            decisions made, names, numbers, dates, versions, identifiers, and stated \
            preferences. Ignore greetings, questions, passing remarks, and anything the \
            assistant said.

            To correct something already recorded, emit the SAME key with the new value; it \
            replaces the old one. Keep keys short, lowercase and stable — the same subject must \
            always land on the same key.

            Output one fact per line as `key: value`, at most %d lines. No bullets, no \
            numbering, no prose, no commentary. If the new message changes nothing, output \
            nothing at all.""";

    /** Memory must be reproducible. Any creativity here shows up as a fact nobody stated. */
    private static final double TEMPERATURE = 0.0;

    private final LlmClient llm;
    private final String model;
    private final int maxFacts;
    private final int maxTokens;
    private final String reasoningEffort;

    public FactExtractor(LlmClient llm, String model, int maxFacts, int maxTokens,
                         String reasoningEffort) {
        this.llm = llm;
        this.model = model;
        this.maxFacts = Math.max(1, maxFacts);
        this.maxTokens = maxTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
    }

    /**
     * Re-derives the fact block from the current one plus the newest user message.
     *
     * @return empty when the block should be left exactly as it is — either nothing usable came
     *         back, or nothing changed. Never returns a block the caller must diff.
     * @throws com.crispyland.agent.llm.LlmException if the call fails; the caller decides whether
     *         a failed extraction is worth failing the user's turn over
     */
    public Optional<Facts> update(Facts current, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        Facts existing = (current == null) ? Facts.EMPTY : current;

        ChatResponse response = llm.complete(new ChatRequest(model,
                List.of(Message.system(INSTRUCTIONS.formatted(maxFacts)),
                        Message.user(brief(existing, userMessage))),
                TEMPERATURE, maxTokens, reasoningEffort, List.of(), null));

        // The upsert means a short reply is no longer data loss — but a *cut* one still is, and
        // worse: the line the ceiling landed in the middle of becomes a fact with half a value,
        // which then outranks the transcript on every later turn. Keep the old block.
        if ("length".equals(response.finishReason())) {
            log.warn("Fact extraction hit the {}-token ceiling and the last line is truncated — "
                    + "keeping the previous facts. Raise agent.facts.max-tokens, or lower "
                    + "agent.facts.reasoning-effort so less of that budget goes to reasoning.",
                    maxTokens);
            return Optional.empty();
        }

        List<Facts.Fact> parsed = parse(response.content());
        // Nothing parseable is now the ordinary answer to a message that settles nothing, not an
        // error — which is why it is no longer worth a WARN.
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        Facts updated = existing.updatedWith(parsed, maxFacts, response.usage().totalTokens());
        if (updated.entries().equals(existing.entries())) {
            // Still billed, but there is nothing to store. Reporting "no change" rather than a
            // new revision keeps the revision counter meaning "the memory actually moved".
            return Optional.empty();
        }
        return Optional.of(updated);
    }

    /**
     * Lines of {@code key: value}, tolerant of the bullets and numbering the instructions ask
     * it not to use. A parser that only accepts the happy path turns a cosmetic deviation into
     * total memory loss.
     */
    private List<Facts.Fact> parse(String content) {
        List<Facts.Fact> parsed = new ArrayList<>();
        if (content == null) {
            return parsed;
        }
        for (String raw : content.strip().split("\\R")) {
            String line = raw.strip().replaceFirst("^(?:[-*\u2022]|\\d+[.)])\\s*", "");
            int colon = line.indexOf(':');
            if (colon <= 0 || colon == line.length() - 1) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.isEmpty() || value.isEmpty() || key.contains(" ") && key.length() > 40) {
                continue;
            }
            parsed.add(new Facts.Fact(key, value));
            if (parsed.size() >= maxFacts) {
                break;
            }
        }
        return parsed;
    }

    private static String brief(Facts existing, String userMessage) {
        return "CURRENT FACTS:\n"
                + (existing.isPresent() ? existing.render() : "(none yet)")
                + "\n\nNEW MESSAGE:\n" + userMessage;
    }
}
