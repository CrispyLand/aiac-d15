package com.crispyland.agent.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the dialogue has settled, as key → value.
 * <p>
 * The summary and this are both rewrites of the past, and they fail in opposite directions.
 * Prose notes keep the story and blur the specifics — "the user picked a database" survives,
 * "Postgres 16, not 15" does not. A fact block keeps only specifics and throws the story away:
 * it cannot tell you why {@code database = Postgres 16} was chosen, but it will never quietly
 * round it off, because a value is replaced wholesale or left alone.
 * <p>
 * Keyed and ordered, not a bag of sentences. The key is what makes an update an <em>update</em>:
 * when the user changes their mind, {@code deadline} is overwritten rather than contradicted by
 * a second note, which is the failure mode that makes appended memory rot.
 *
 * @param entries     ordered key → value pairs; insertion order is kept so the block reads stably
 * @param revision    how many times the block has been rewritten; 0 means never
 * @param buildTokens total tokens spent on the extraction calls that maintain it — the running
 *                    bill for this strategy, which is what makes it comparable to the others
 */
public record Facts(List<Fact> entries, int revision, long buildTokens) {

    public static final Facts EMPTY = new Facts(List.of(), 0, 0L);

    /**
     * One remembered thing, and whether it is still scratch or has been settled.
     *
     * @param settled true for a line the extractor tagged {@code decision} — kept here rather
     *                than in long-term while the task is open, and promoted out of here when the
     *                task closes. Everything else is scratch and is discarded at that point.
     */
    public record Fact(String key, String value, boolean settled) {

        public Fact {
            key = (key == null) ? "" : key.strip();
            value = (value == null) ? "" : value.strip();
        }

        /** Ordinary task scratch — the common case, and what most callers mean. */
        public Fact(String key, String value) {
            this(key, value, false);
        }

        public boolean isPresent() {
            return !key.isEmpty() && !value.isEmpty();
        }
    }

    public Facts {
        entries = (entries == null) ? List.of() : List.copyOf(entries);
    }

    public boolean isPresent() {
        return !entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * The block as it is sent to the model: one {@code key: value} per line, with settled lines
     * marked. The mark is not decoration — it is the difference between "this is where we are"
     * and "this is agreed", and a model that cannot tell them apart reopens closed questions.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (Fact fact : entries) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("- ").append(fact.settled() ? "[agreed] " : "")
                    .append(fact.key()).append(": ").append(fact.value());
        }
        return text.toString();
    }

    /** The settled lines, in order — what a closing task hands to long-term memory. */
    public List<Fact> settled() {
        return entries.stream().filter(Fact::settled).toList();
    }

    /**
     * Applies an extraction on top of the block and adds what it cost. A returned key overwrites,
     * a new key is appended, and a key the extraction did not mention is left exactly as it was.
     * <p>
     * Upsert rather than wholesale replacement, because replacement makes the whole memory only
     * as reliable as the single flakiest call: asked to re-emit eleven standing facts alongside a
     * twelfth, gpt-oss eventually returned just the twelfth, and a block that is replaced by what
     * comes back loses the other ten — silently, since there is no shorter reply to notice. The
     * upsert also lets the extractor be asked for only what changed, which is a much smaller and
     * much easier reply than the whole block.
     * <p>
     * The price is that nothing is ever deleted, only overwritten. That is the right trade for a
     * memory of decisions: a superseded decision arrives as a new value under the same key, and
     * the cap below bounds what unbounded growth would otherwise cost.
     *
     * @param maxFacts ceiling on the block; {@code <= 0} for none. Updates to keys already in the
     *                 block are always applied — the cap refuses new subjects, never corrections.
     */
    /**
     * Adds what an extraction cost without pretending the block moved.
     * <p>
     * The one call per turn now feeds working <em>and</em> long-term memory, so a turn that only
     * taught the agent something about the person still has to be paid for somewhere, and this is
     * the layer carrying the running total. Billing it through {@link #updatedWith} instead would
     * advance the revision counter on turns where nothing here changed, and that counter is the
     * only cheap signal that working memory is actually being maintained.
     */
    public Facts billed(long costTokens) {
        return (costTokens <= 0) ? this : new Facts(entries, revision, buildTokens + costTokens);
    }

    public Facts updatedWith(List<Fact> changes, int maxFacts, long costTokens) {
        Map<String, Fact> merged = new LinkedHashMap<>();
        for (Fact fact : entries) {
            merged.put(fact.key(), fact);
        }
        for (Fact fact : (changes == null) ? List.<Fact>of() : changes) {
            if (fact != null && fact.isPresent()
                    && (merged.containsKey(fact.key()) || maxFacts <= 0 || merged.size() < maxFacts)) {
                merged.put(fact.key(), fact);
            }
        }
        return new Facts(List.copyOf(merged.values()), revision + 1, buildTokens + costTokens);
    }
}
