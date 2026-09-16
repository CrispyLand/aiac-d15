package com.crispyland.agent.memory;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads one user message and proposes what in it is worth keeping, each line labelled with the
 * kind of thing it is.
 * <p>
 * The model's entire job here is <em>labelling</em>. It never names a store, a layer or a
 * lifetime; it says "this is a profile line", and {@link MemoryRouter} decides — in Java, from a
 * fixed table — where a profile line lives and for how long. Splitting it this way is what makes
 * "explicitly choose what is saved where" a property of the codebase rather than of a prompt: the
 * half that is allowed to be creative cannot reach the filesystem, and the half that reaches the
 * filesystem cannot be creative.
 * <p>
 * One call, not one per layer. Deciding whether a sentence is task scratch or a durable fact about
 * the person needs the same sentence in front of you either way, and three calls asking three
 * narrower questions cost three times as much to produce answers that then have to be reconciled
 * when two of them claim the same line.
 * <p>
 * The model is shown the new message and the <em>keys</em> already in use — never their values.
 * Keys are the minimum that makes an upsert actually update: without them gpt-oss answered
 * "Postgres 16 it is, final" with {@code postgres: 16} while {@code database: Postgres 16} was
 * already held, and two keys for one subject can both be true at once with only one of them
 * current. Withholding the values is what keeps this from becoming the earlier design, which
 * showed the whole block and asked for a delta: given the values, the model eventually re-emits
 * standing facts, and on the turn it emits only some of them a block that trusts the reply has
 * silently lost the rest. A list of keys has nothing in it to re-emit.
 * <p>
 * Extracted from the user's message only, never from the assistant's reply. A reply is the model's
 * own prose, and promoting it into remembered fact would launder a hallucination into something
 * every later turn is told to trust.
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /**
     * The instruction that does the real work is "keep keys short, lowercase and stable": two
     * spellings of the same subject defeat the upsert entirely, because {@code db} and
     * {@code database} can both be held at once and only one of them is current.
     * <p>
     * The tag menu is generated from {@link MemoryTag} rather than typed out here, so a tag the
     * prompt offers and a tag the router routes cannot drift apart. A prompt listing a fifth tag
     * the table has never heard of produces lines that are extracted, paid for, and dropped.
     */
    private static final String INSTRUCTIONS = """
            You read one message from a user and pull out what is worth remembering.

            Label every line with exactly one of these tags:
            %s

            Output one line per thing worth keeping, in the form `tag/key: value`, at most %d \
            lines. The value is what is known, stated plainly.

            The key names the SUBJECT being talked about, in one or two lowercase words. It never \
            names the speaker's stance towards it: a message about which database to use gets the \
            key `database` whether the user is leaning towards one, has picked one, or has just \
            changed their mind. Words like `leaning`, `choice`, `decision`, `update`, `final` and \
            `version` are stances, not subjects, and must never be keys — put that in the value \
            or in the tag instead.

            If KEYS IN USE lists a key for the subject you are recording, you MUST reuse that \
            exact key — that is how a correction replaces what is held instead of sitting next to \
            it. Only invent a key for a subject that is not already listed.

            Example. Given the message "I'm Nur, a Java dev, keep answers short — leaning \
            Postgres 16 here, due end of Q3", output exactly:
            profile/name: Nur
            profile/role: Java developer
            profile/preference: short answers
            task/database: Postgres 16, still only leaning
            task/deadline: end of Q3

            Record specifics: goals, hard requirements, constraints, decisions, names, numbers, \
            dates, versions, identifiers and stated preferences. Ignore greetings, questions, \
            politeness and anything that is merely being discussed rather than established.

            When a line could carry two tags, pick the shorter-lived one. Something the user is \
            leaning towards, weighing up or has not committed to is `task`, not `decision`; \
            anything that only matters until this job is done is `task`, not `knowledge`.

            Output nothing but those lines — no bullets, no numbering, no prose, no commentary. \
            If the message establishes nothing at all, output `none: nothing to keep`.""";

    /** Memory must be reproducible. Any creativity here shows up as a fact nobody stated. */
    private static final double TEMPERATURE = 0.0;

    /**
     * What one extraction call produced, and what it cost.
     *
     * @param lines      labelled but unrouted — this class deliberately cannot say where they go
     * @param costTokens the whole call, billed to whichever layer is keeping the running total
     */
    public record Extraction(List<MemoryRouter.Line> lines, long costTokens) {

        public static final Extraction NOTHING = new Extraction(List.of(), 0L);

        public Extraction {
            lines = List.copyOf(lines);
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    private final LlmClient llm;
    private final String model;
    private final int maxLines;
    private final int maxTokens;
    private final String reasoningEffort;

    public MemoryExtractor(LlmClient llm, String model, int maxLines, int maxTokens,
                           String reasoningEffort) {
        this.llm = llm;
        this.model = model;
        this.maxLines = Math.max(1, maxLines);
        this.maxTokens = maxTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank()) ? null : reasoningEffort;
    }

    /**
     * Labels whatever the newest user message establishes.
     *
     * @param keysInUse every key the layers already hold, whichever layer holds it. Flat and
     *                  unqualified on purpose: the model is not being asked where a subject lives,
     *                  only what it is called, and telling it which layer a key came from invites
     *                  it to keep the subject there rather than re-label it when it moves
     * @return {@link Extraction#NOTHING} when there is nothing usable to route — including when
     *         the reply was cut off, which is not the same as an empty reply and must not be
     *         treated as one
     * @throws com.crispyland.agent.llm.LlmException if the call fails; the caller decides whether
     *         a failed extraction is worth failing the user's turn over
     */
    public Extraction extract(String userMessage, Collection<String> keysInUse) {
        if (userMessage == null || userMessage.isBlank()) {
            return Extraction.NOTHING;
        }

        ChatResponse response = llm.complete(new ChatRequest(model,
                List.of(Message.system(INSTRUCTIONS.formatted(menu(), maxLines)),
                        Message.user(brief(userMessage, keysInUse))),
                TEMPERATURE, maxTokens, reasoningEffort, List.of(), null));

        // A truncated reply is worse than an empty one: the line the ceiling landed in the middle
        // of parses as a tag and a key with half a value, and that half then outranks the
        // transcript on every later turn. Keep nothing rather than keep a fragment.
        if ("length".equals(response.finishReason())) {
            log.warn("Memory extraction hit the {}-token ceiling and the last line is truncated — "
                            + "keeping nothing from this turn. Raise agent.facts.max-tokens, or "
                            + "lower agent.facts.reasoning-effort so less of that budget goes to "
                            + "reasoning.",
                    maxTokens);
            return Extraction.NOTHING;
        }

        List<MemoryRouter.Line> lines = parse(response.content());
        if (lines.isEmpty()) {
            // The ordinary answer to a message that settles nothing, not a failure — and so not
            // worth a WARN. The `none` tag exists so the model can say this out loud, but a
            // genuinely empty reply means the same thing.
            return Extraction.NOTHING;
        }
        return new Extraction(lines, response.usage().totalTokens());
    }

    /** Keys first, then the message. Omitted entirely when nothing is held, so a first turn is
     * not shown an empty heading it has to interpret. */
    private static String brief(String userMessage, Collection<String> keysInUse) {
        if (keysInUse == null || keysInUse.isEmpty()) {
            return "NEW MESSAGE:\n" + userMessage;
        }
        return "KEYS IN USE:\n" + String.join(", ", keysInUse) + "\n\nNEW MESSAGE:\n" + userMessage;
    }

    /** The tag menu as the prompt shows it — one line per tag, straight off the routing table. */
    private static String menu() {
        StringBuilder text = new StringBuilder();
        for (MemoryTag tag : MemoryTag.values()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("- ").append(tag.id()).append(" — ").append(tag.what());
        }
        return text.toString();
    }

    /**
     * Lines of {@code tag/key: value}, tolerant of the bullets and numbering the instructions ask
     * it not to use, and of {@code tag: key: value} — the one deviation a model reliably makes,
     * because a colon is the more natural separator after a word it has just been told is a label.
     * A parser that accepts only the happy path turns a cosmetic deviation into total memory loss.
     * <p>
     * Tags are passed through verbatim rather than resolved here. An unrecognised one has to reach
     * the router to be dropped and logged in one place, or "the model used a tag we do not have"
     * becomes a thing that can happen silently in two different files.
     */
    private List<MemoryRouter.Line> parse(String content) {
        List<MemoryRouter.Line> parsed = new ArrayList<>();
        if (content == null) {
            return parsed;
        }
        for (String raw : content.strip().split("\\R")) {
            String line = raw.strip().replaceFirst("^(?:[-*\u2022]|\\d+[.)])\\s*", "");
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String head = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();

            int slash = head.indexOf('/');
            String tag;
            String key;
            String value;
            if (slash > 0) {
                tag = head.substring(0, slash);
                key = head.substring(slash + 1);
                value = rest;
            } else {
                // `tag: key: value`. Without a second colon the line carries no subject, which is
                // exactly the shape of `none: nothing to keep` — kept as a tag with no key so the
                // router can recognise a deliberate silence instead of inferring one from absence.
                tag = head;
                int second = rest.indexOf(':');
                key = (second <= 0) ? "" : rest.substring(0, second);
                value = (second <= 0) ? rest : rest.substring(second + 1);
            }

            MemoryRouter.Line candidate = new MemoryRouter.Line(tag.strip().toLowerCase(Locale.ROOT),
                    key.strip().toLowerCase(Locale.ROOT), value.strip());
            if (candidate.tag().isEmpty()) {
                continue;
            }
            // A tagless subject is unroutable, and a subject with no value is a key that would
            // overwrite a real one with nothing. Both are dropped before they reach the router.
            if (MemoryTag.from(candidate.tag()) != MemoryTag.NONE
                    && (candidate.key().isEmpty() || candidate.value().isEmpty())) {
                continue;
            }
            parsed.add(candidate);
            if (parsed.size() >= maxLines) {
                break;
            }
        }
        return parsed;
    }
}
