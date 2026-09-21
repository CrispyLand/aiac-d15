package com.crispyland.agent.invariant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One rule the agent is not allowed to break, and everything needed to enforce and explain it.
 * <p>
 * The distinction from {@code LongTermKind.DECISION} is not a shade of meaning, it is the opposite
 * instruction. Long-term memory is sent with the words <em>"let anything in this conversation
 * override it"</em>; an invariant is precisely the thing a conversation may not override. Storing
 * both in one block would make that block contradict itself, which is why this lives in its own
 * file, its own store and its own prompt block.
 * <p>
 * Three of the fields exist only so a refusal can be an argument instead of a wall:
 * {@link #rule} is what was broken, {@link #why} is the reason anyone agreed to it, and
 * {@link #instead} is the way forward. {@code instead} is written by the author rather than
 * generated at refusal time, because the person who imposed a constraint is the person who knows
 * the way around it — and because generating it would cost a call on the one path designed not to
 * need one.
 *
 * @param id         short and citable ({@code inv-3}). A guard names this, and Java checks the name
 *                   against the stored set before acting, so a model cannot cite a rule into
 *                   existence any more than it can invent a task transition
 * @param watch      literal terms that make an answer worth checking; see {@link Check}
 * @param active     false once retired. Retired rather than deleted, because a rule that was
 *                   lifted is itself a decision, and the reason it was lifted is the interesting
 *                   half of it
 * @param retiredWhy why it stopped binding; empty while it still binds
 */
public record Invariant(String id, InvariantKind kind, InvariantScope scope, Check check,
                        String rule, String why, String instead, List<String> watch,
                        boolean active, String retiredWhy) {

    /** Ids the UI mints, and the prefix {@code Invariants.nextId} counts from. */
    public static final String ID_PREFIX = "inv-";

    /**
     * Neither side of a watched term may be a letter, digit or underscore.
     * <p>
     * Plain {@code contains} is the bug this exists to avoid: {@code "redis"} occurs inside
     * {@code "rediscover"}, so a stack rule about Redis would refuse a sentence about rediscovering
     * requirements. {@code \p{L}} rather than {@code \w} because invariants get written in Russian
     * here, and {@code \w} would treat every Cyrillic letter as a boundary.
     */
    private static final String BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String AFTER = "(?![\\p{L}\\p{N}_])";

    public Invariant {
        id = normalizeId(id);
        kind = (kind == null) ? InvariantKind.TECHNICAL : kind;
        scope = (scope == null) ? InvariantScope.GLOBAL : scope;
        rule = strip(rule);
        why = strip(why);
        instead = strip(instead);
        watch = normalizeWatch(watch);
        retiredWhy = strip(retiredWhy);
        // A FORBID or WATCH rule with nothing to watch for can never fire, which would leave it
        // enforced by nothing at all. Falling back to MODEL makes it expensive rather than absent.
        check = (check == null) ? Check.MODEL
                : (check.needsTerms() && watch.isEmpty()) ? Check.MODEL : check;
    }

    /** A rule needs an identity and something to say; anything less is not enforceable. */
    public boolean isPresent() {
        return !id.isEmpty() && !rule.isEmpty();
    }

    /** True when this rule is currently binding — present, and not retired. */
    public boolean binds() {
        return active && isPresent();
    }

    /** How the id reads on the page and in a refusal: {@code INV-3}. */
    public String label() {
        return id.toUpperCase(Locale.ROOT);
    }

    public Invariant withId(String newId) {
        return new Invariant(newId, kind, scope, check, rule, why, instead, watch, active, retiredWhy);
    }

    /** Stops the rule binding, keeping it and the reason on the record. */
    public Invariant retire(String reason) {
        return active
                ? new Invariant(id, kind, scope, check, rule, why, instead, watch, false, reason)
                : this;
    }

    /** Puts it back, dropping the retirement reason — it is no longer true of the rule. */
    public Invariant restore() {
        return active
                ? this
                : new Invariant(id, kind, scope, check, rule, why, instead, watch, true, "");
    }

    /**
     * Which watched terms appear in a piece of text, as whole words, ignoring case.
     * <p>
     * Patterns are compiled per call rather than cached, because a record cannot hold derived state
     * without becoming something other than a record, and the cost is nothing beside the model call
     * this method exists to avoid making.
     *
     * @return the terms found, in the order they were declared; empty when nothing matched
     */
    public List<String> matches(String text) {
        if (text == null || text.isBlank() || watch.isEmpty()) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        for (String term : watch) {
            if (pattern(term).matcher(text).find()) {
                hits.add(term);
            }
        }
        return List.copyOf(hits);
    }

    /**
     * The block as the model is sent it: the rule, why it exists, and the way around it.
     * <p>
     * {@code instead} is included deliberately. A model told only what it may not do will refuse
     * and stop; told what to do in its place, it can honour the rule in the first answer rather
     * than being caught breaking it and made to try again — which is one call cheaper and reads
     * like a colleague rather than a filter.
     */
    public String render() {
        StringBuilder text = new StringBuilder("- [").append(label()).append("] ").append(rule);
        if (!why.isEmpty()) {
            text.append("\n    why: ").append(why);
        }
        if (!instead.isEmpty()) {
            text.append("\n    instead: ").append(instead);
        }
        return text.toString();
    }

    /**
     * The refusal, composed from the rule rather than improvised.
     * <p>
     * Deliberately not phrased as an error. Nothing malfunctioned: a commitment was made earlier
     * and is being kept. The three parts are what make it answerable — a reader who disagrees can
     * see which rule to argue with, and the only way past it is to change that rule.
     */
    public String redirect() {
        StringBuilder text = new StringBuilder("I can't take this direction: ").append(clause(rule));
        if (!why.isEmpty()) {
            text.append(", because ").append(clause(why));
        }
        text.append(" (").append(label()).append(").");
        if (!instead.isEmpty()) {
            text.append(" What I can do instead: ").append(sentence(instead));
        }
        return text.toString();
    }

    /** Trailing stop removed, so the sentence this is spliced into does not gain a second one. */
    private static String clause(String value) {
        String stripped = value.strip();
        return stripped.endsWith(".") ? stripped.substring(0, stripped.length() - 1) : stripped;
    }

    private static String sentence(String value) {
        String stripped = value.strip();
        return stripped.endsWith(".") || stripped.endsWith("!") || stripped.endsWith("?")
                ? stripped : stripped + ".";
    }

    private static Pattern pattern(String term) {
        return Pattern.compile(BEFORE + Pattern.quote(term) + AFTER,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** Lowercased and hyphenated, so the same rule cannot be cited under two spellings. */
    private static String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        return id.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    /** Deduplicated case-insensitively, keeping declaration order and dropping blanks. */
    private static List<String> normalizeWatch(List<String> watch) {
        if (watch == null || watch.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String term : watch) {
            if (term != null && !term.isBlank()) {
                seen.add(term.strip());
            }
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> kept = new ArrayList<>();
        for (String term : seen) {
            if (unique.add(term.toLowerCase(Locale.ROOT))) {
                kept.add(term);
            }
        }
        return List.copyOf(kept);
    }

    private static String strip(String value) {
        return (value == null) ? "" : value.strip();
    }
}
