package com.crispyland.agent.invariant;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.Message;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether what has just been asked for would break a standing rule — and does it for
 * nothing whenever it can.
 * <p>
 * The block in the prompt is not enforcement. It makes compliance the easy path, which is worth
 * having, but a rule the model is merely told about is a rule it is free to forget once the
 * conversation is long enough. This class is the part that does not forget.
 * <p>
 * Three tiers, cheapest first, and the tiering is the whole design:
 * <ol>
 *   <li>{@link Check#FORBID} — a watched term appearing at all <em>is</em> the breach. Settled by
 *       a regex, no call, no tokens. "Do not propose Mongo" is this shape, and so is most of what
 *       people actually write down.</li>
 *   <li>{@link Check#WATCH} — a watched term is a reason to look, not a verdict. "Can we drop
 *       Redis?" mentions Redis and breaks nothing. Only a rule whose term actually hit is put to
 *       the model, so the call is paid for on the turns that earned it.</li>
 *   <li>{@link Check#MODEL} — nothing literal gives the rule away, so it is asked about every
 *       turn. "Never quote below cost" has no term to search for. This is the expensive tier and
 *       it exists because the alternative is a rule enforced by nothing at all.</li>
 * </ol>
 * The second and third tiers share one call: they are the same question about the same message,
 * and asking twice would double the bill to re-read text the first call already had.
 * <p>
 * Only the user's message is checked, not the answer. Checking the answer means paying for the
 * answer first and then throwing it away, which on a free tier is the difference between a guard
 * you can afford to run and one you switch off. Refusing at the request is also the more honest
 * thing to show someone: nothing was drafted, so there is nothing being withheld.
 * <p>
 * What Java keeps for itself is the veto. The model proposes <em>which</em> rule was broken;
 * {@link Invariants#cited} decides whether that rule exists, still binds, and was among the ones
 * actually in question. A hallucinated {@code INV-9} refuses nothing.
 */
public class InvariantGuard {

    private static final Logger log = LoggerFactory.getLogger(InvariantGuard.class);

    /**
     * Two things are asked for and the second is the one that gets ignored without it spelled out:
     * the model must rule on the <em>request</em>, not on the wording. A message that argues with
     * a rule, asks why it exists, or proposes retiring it is not a breach of it — treating it as
     * one produces an assistant that cannot be asked about its own constraints, which is worse
     * than useless because the only way out of a rule is to talk about it.
     */
    private static final String INSTRUCTIONS = """
            You check one message from a user against standing rules that the assistant must not \
            break. You are not answering the message.

            Decide only this: would doing what this message asks for require breaking one of the \
            rules below?

            Say `ok` when it would not. Say `ok` in particular when the message merely mentions \
            something a rule is about, asks why a rule exists, argues with a rule, or asks to \
            change or drop one. Discussing a rule is never breaking it, and neither is naming the \
            thing it forbids.

            When it genuinely would, answer with one line and nothing else:
            `<rule-id>: <one short sentence on what would be broken>`

            The rule id must be one of the ids listed below, copied exactly. Never invent one. If \
            two rules would be broken, name the first.

            Output `ok`, or that one line. No prose, no explanation, no commentary.""";

    /** A ruling has to be reproducible: the same message must not be refused only sometimes. */
    private static final double TEMPERATURE = 0.0;

    /**
     * What the guard decided, and what finding out cost.
     *
     * @param broken     the rule that would be broken, or {@code null} when nothing would be
     * @param terms      the watched terms that gave it away; empty when the model settled it
     * @param costTokens the whole call, or 0 when Java settled it and no call was made
     */
    public record Ruling(Invariant broken, List<String> terms, long costTokens) {

        public static final Ruling CLEAR = new Ruling(null, List.of(), 0L);

        public Ruling {
            terms = List.copyOf(terms);
        }

        public boolean breached() {
            return broken != null;
        }

        /** Settled by a term search rather than by a call — the free tier. */
        public boolean settledInJava() {
            return breached() && !terms.isEmpty();
        }

        /** What the user is told: the rule, the reason for it, and the way forward. */
        public String redirect() {
            return breached() ? broken.redirect() : "";
        }
    }

    private final LlmClient llm;
    private final String model;
    private final int maxTokens;
    private final String reasoningEffort;

    public InvariantGuard(LlmClient llm, String model, int maxTokens, String reasoningEffort) {
        this.llm = llm;
        this.model = model;
        this.maxTokens = maxTokens;
        this.reasoningEffort = (reasoningEffort == null || reasoningEffort.isBlank())
                ? null : reasoningEffort;
    }

    /**
     * Rules on one message.
     * <p>
     * A failed call returns {@link Ruling#CLEAR} rather than refusing the turn, and that is a
     * deliberate choice with a cost attached. Failing closed would mean a provider timeout
     * silently turns into "you are not allowed to ask that", which is the least explicable error
     * an assistant can produce and one the user cannot do anything about. Failing open means one
     * turn goes unchecked at the upper tiers — the {@code forbid} tier is unaffected, because it
     * never depended on a call in the first place. It is logged at WARN so the gap is visible.
     */
    public Ruling check(String userMessage, Invariants invariants) {
        if (userMessage == null || userMessage.isBlank()
                || invariants == null || !invariants.isPresent()) {
            return Ruling.CLEAR;
        }

        List<Invariants.Hit> hits = invariants.hits(userMessage);
        for (Invariants.Hit hit : hits) {
            if (hit.decidesAlone()) {
                log.info("Refused by {} on the term(s) {} — settled in Java, no call made.",
                        hit.invariant().label(), hit.terms());
                return new Ruling(hit.invariant(), hit.terms(), 0L);
            }
        }

        List<Invariant> candidates = candidates(hits, invariants);
        if (candidates.isEmpty()) {
            return Ruling.CLEAR;
        }

        ChatResponse response;
        try {
            response = llm.complete(new ChatRequest(model,
                    List.of(Message.system(INSTRUCTIONS), Message.user(brief(userMessage, candidates))),
                    TEMPERATURE, maxTokens, reasoningEffort, List.of(), null));
        } catch (RuntimeException e) {
            log.warn("Invariant guard failed ({}) — this turn goes unchecked against {} rule(s) "
                    + "that need a call. Rules enforced by a term search are unaffected.",
                    e.getMessage(), candidates.size());
            return Ruling.CLEAR;
        }

        // A truncated reply is not a verdict. The ceiling can land mid-id, and half an id either
        // fails to resolve or — worse — resolves to a different rule than the one meant.
        if ("length".equals(response.finishReason())) {
            log.warn("Invariant guard hit the {}-token ceiling before answering — treating this "
                    + "turn as unchecked. Raise agent.invariants.max-tokens, or lower "
                    + "agent.invariants.reasoning-effort.", maxTokens);
            return Ruling.CLEAR;
        }

        long cost = response.usage().totalTokens();
        Invariant broken = resolve(response.content(), candidates, invariants);
        if (broken == null) {
            return new Ruling(null, List.of(), cost);
        }
        log.info("Refused by {} — the guard ruled on it, {} token(s).", broken.label(), cost);
        return new Ruling(broken, List.of(), cost);
    }

    /**
     * Everything that still needs asking about: the watched rules whose terms hit, plus every rule
     * with no terms at all.
     * <p>
     * Deliberately not the whole set. Sending rules that nothing in the message came near pays for
     * tokens on every turn to re-state constraints that were never in question, and gives the
     * model more chances to pick the wrong one out of a longer list.
     */
    private static List<Invariant> candidates(List<Invariants.Hit> hits, Invariants invariants) {
        LinkedHashSet<Invariant> wanted = new LinkedHashSet<>();
        hits.forEach(hit -> wanted.add(hit.invariant()));
        wanted.addAll(invariants.alwaysChecked());
        return List.copyOf(wanted);
    }

    /**
     * The rule the guard named, once Java has agreed that it is a real one.
     * <p>
     * Two checks, not one. {@link Invariants#cited} says the id exists and still binds; the
     * membership check says it was one of the rules actually put to the model. Without the second,
     * a model shown two rules can refuse on the authority of a third it happened to remember from
     * the prompt block — a real rule, cited for a message it was never asked about.
     */
    private static Invariant resolve(String content, List<Invariant> candidates,
                                     Invariants invariants) {
        if (content == null) {
            return null;
        }
        String reply = content.strip();
        if (reply.isEmpty() || reply.toLowerCase(Locale.ROOT).startsWith("ok")) {
            return null;
        }

        String head = reply.split("\\R", 2)[0];
        int colon = head.indexOf(':');
        String id = (colon <= 0) ? head.strip() : head.substring(0, colon).strip();

        Invariant cited = invariants.cited(id);
        if (cited == null) {
            log.warn("The guard cited '{}', which is not a rule that binds. Nothing refused.", id);
            return null;
        }
        if (!candidates.contains(cited)) {
            log.warn("The guard cited {}, which was not among the rules it was shown. Nothing "
                    + "refused.", cited.label());
            return null;
        }
        return cited;
    }

    /** The rules in question, then the message — in that order, so the criteria are read first. */
    private static String brief(String userMessage, List<Invariant> candidates) {
        List<String> rendered = new ArrayList<>(candidates.size());
        for (Invariant candidate : candidates) {
            rendered.add(candidate.render());
        }
        return "RULES:\n" + String.join("\n", rendered) + "\n\nMESSAGE:\n" + userMessage;
    }
}
