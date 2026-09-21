package com.crispyland.agent.invariant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The set of rules in force, plus the ones that used to be.
 * <p>
 * Shaped like {@link com.crispyland.agent.memory.LongTermMemory} — ordered, addressed by a stable
 * id, upserted rather than appended — because the same failure applies: a list that can only grow
 * ends up holding a rule and its own contradiction, and nothing in it says which one is current.
 * <p>
 * Retired rules stay in the list. They are excluded from {@link #render} so they cost nothing at
 * the model, and kept everywhere else so the page can show that a constraint was lifted, by whom
 * and why. A rule that vanishes when it stops applying takes the most interesting part of its
 * history with it.
 *
 * @param revision how many times the set has changed; 0 means nothing was ever declared
 */
public record Invariants(List<Invariant> all, int revision) {

    public static final Invariants EMPTY = new Invariants(List.of(), 0);

    public Invariants {
        all = (all == null) ? List.of() : List.copyOf(all);
    }

    /** True when anything is actually binding. A set of only retired rules is not present. */
    public boolean isPresent() {
        return all.stream().anyMatch(Invariant::binds);
    }

    /** Everything currently in force, in declaration order. */
    public List<Invariant> binding() {
        return all.stream().filter(Invariant::binds).toList();
    }

    /** Everything that used to be in force — what the page lists under its own heading. */
    public List<Invariant> retired() {
        return all.stream().filter(held -> !held.active()).toList();
    }

    /** Binding rules of one kind, for grouping on the page and in the block. */
    public List<Invariant> of(InvariantKind kind) {
        return all.stream().filter(held -> held.binds() && held.kind() == kind).toList();
    }

    /** Binding rules of one scope, for merging a task's rules with the standing ones. */
    public List<Invariant> inScope(InvariantScope scope) {
        return all.stream().filter(held -> held.binds() && held.scope() == scope).toList();
    }

    public int size() {
        return all.size();
    }

    /**
     * The rule a guard named, or {@code null} if it named one that is unknown or already retired.
     * <p>
     * This is the check that keeps the hybrid honest. The model proposes <em>which</em> rule was
     * broken; Java decides whether that rule exists and still binds. Without it, a hallucinated
     * {@code INV-9} would refuse an answer on the authority of nothing — the same reasoning that
     * makes {@code TaskStage} a fixed table rather than whatever came back from the last call.
     */
    public Invariant cited(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (Invariant held : all) {
            if (held.id().equals(wanted) && held.binds()) {
                return held;
            }
        }
        return null;
    }

    /**
     * Every rule whose watched terms appear in the text, paired with the terms that hit.
     * <p>
     * One pass over the set, in Java, before anything is sent anywhere. What the caller does with
     * a hit depends on the rule's {@link Check}: settle it, or spend a call on it.
     */
    public List<Hit> hits(String text) {
        List<Hit> found = new ArrayList<>();
        for (Invariant held : all) {
            if (!held.binds() || !held.check().needsTerms()) {
                continue;
            }
            List<String> terms = held.matches(text);
            if (!terms.isEmpty()) {
                found.add(new Hit(held, terms));
            }
        }
        return List.copyOf(found);
    }

    /** Rules that have to be asked about on every turn, because nothing literal gives them away. */
    public List<Invariant> alwaysChecked() {
        return all.stream().filter(held -> held.binds() && held.check() == Check.MODEL).toList();
    }

    /** A rule matched by one or more of its watched terms. */
    public record Hit(Invariant invariant, List<String> terms) {

        public Hit {
            terms = List.copyOf(terms);
        }

        /** True when the rule settles it in Java and no call is needed. */
        public boolean decidesAlone() {
            return invariant.check().decidesAlone();
        }
    }

    /**
     * The block as the model is sent it, grouped under its kind headings and binding rules only.
     * <p>
     * Grouped for the same reason long-term memory is: the heading is what tells the model how much
     * room there is to negotiate. A stack constraint and a business rule read as equally rigid in a
     * flat list, and they are not.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (InvariantKind kind : InvariantKind.values()) {
            List<Invariant> group = of(kind);
            if (group.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(kind.label()).append(" (").append(kind.tagline()).append("):");
            for (Invariant held : group) {
                text.append('\n').append(held.render());
            }
        }
        return text.toString();
    }

    /**
     * Declares a rule, or replaces one already held under the same id, keeping its position.
     * <p>
     * A blank id is assigned the next free one, because only the whole set knows what is free.
     * Replacing in place rather than appending matters for the same reason it does in long-term
     * memory: an amended rule that moves to the end reads as a new commitment rather than a
     * revision of an old one.
     */
    public Invariants with(Invariant invariant) {
        if (invariant == null || invariant.rule().isEmpty()) {
            return this;
        }
        Invariant declared = invariant.id().isEmpty() ? invariant.withId(nextId()) : invariant;
        Map<String, Invariant> merged = new LinkedHashMap<>();
        for (Invariant held : all) {
            merged.put(held.id(), held);
        }
        merged.put(declared.id(), declared);
        return new Invariants(List.copyOf(merged.values()), revision + 1);
    }

    /** Stops a rule binding, with a reason. Unknown ids are not an error. */
    public Invariants retire(String id, String reason) {
        return replace(id, held -> held.retire(reason));
    }

    /** Puts a retired rule back in force. */
    public Invariants restore(String id) {
        return replace(id, Invariant::restore);
    }

    /**
     * The next free id. Counts from the highest {@code inv-N} already held rather than from the
     * size, so retiring rules never hands a new one an id somebody has already cited.
     */
    public String nextId() {
        int highest = 0;
        for (Invariant held : all) {
            if (!held.id().startsWith(Invariant.ID_PREFIX)) {
                continue;
            }
            try {
                highest = Math.max(highest,
                        Integer.parseInt(held.id().substring(Invariant.ID_PREFIX.length())));
            } catch (NumberFormatException e) {
                // An id somebody typed by hand. It keeps its name and does not feed the counter.
            }
        }
        return Invariant.ID_PREFIX + (highest + 1);
    }

    private Invariants replace(String id, UnaryOperator<Invariant> change) {
        if (id == null) {
            return this;
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        List<Invariant> rebuilt = new ArrayList<>(all.size());
        boolean changed = false;
        for (Invariant held : all) {
            if (held.id().equals(wanted)) {
                Invariant updated = change.apply(held);
                changed = changed || !updated.equals(held);
                rebuilt.add(updated);
            } else {
                rebuilt.add(held);
            }
        }
        return changed ? new Invariants(rebuilt, revision + 1) : this;
    }
}
