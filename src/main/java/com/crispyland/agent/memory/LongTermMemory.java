package com.crispyland.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the agent keeps about a visitor after every conversation they had is gone.
 * <p>
 * Shaped like {@link Facts} — keyed, ordered, upserted — for the same reason: a key is what makes
 * a change an <em>update</em> rather than a contradiction, and appended memory that can only
 * contradict itself is the failure mode that makes long-lived memory rot. The difference is the
 * {@link LongTermKind} in front of the key. Working memory is one flat block because it dies with
 * the task and nobody needs to audit it; this one outlives every conversation, so each entry has
 * to say which bucket it belongs to and therefore what a wrong entry costs.
 * <p>
 * Entries are addressed by {@link Entry#id()} rather than by index, because the only handle the
 * forget button can safely hold is one that does not move when a neighbouring entry is removed.
 *
 * @param entries  ordered {@code kind:key → value}; insertion order is kept so the block reads
 *                 stably and the newest thing learned is always last
 * @param revision how many times the block has been rewritten; 0 means never
 */
public record LongTermMemory(List<Entry> entries, int revision) {

    public static final LongTermMemory EMPTY = new LongTermMemory(List.of(), 0);

    /**
     * One remembered thing and the reason the agent is allowed to use it.
     *
     * @param kind  which of the three long-term buckets this belongs to
     * @param key   the subject, unique within the whole block — not within the kind, so the same
     *              subject cannot be filed twice under two different kinds and answered twice
     * @param value what is known about it
     */
    public record Entry(LongTermKind kind, String key, String value) {

        public Entry {
            key = (key == null) ? "" : key.strip();
            value = (value == null) ? "" : value.strip();
        }

        public boolean isPresent() {
            return kind != null && !key.isEmpty() && !value.isEmpty();
        }

        /** The stable handle the forget button posts back. */
        public String id() {
            return kind.id() + ":" + key.toLowerCase(Locale.ROOT);
        }
    }

    public LongTermMemory {
        entries = (entries == null) ? List.of() : List.copyOf(entries);
    }

    public boolean isPresent() {
        return !entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** The entries of one kind, in the order they were learned — what the UI panel lists. */
    public List<Entry> of(LongTermKind kind) {
        return entries.stream().filter(entry -> entry.kind() == kind).toList();
    }

    /**
     * The block as it is sent to the model, grouped under its kind headings.
     * <p>
     * Grouped rather than a flat list because the heading is the only thing telling the model how
     * much authority a line carries: {@code Decisions} is something it must build on, whereas
     * {@code What you have told me} is background it may contradict if the user says otherwise.
     * Flattened into one list, every line reads as equally binding.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        for (LongTermKind kind : LongTermKind.values()) {
            List<Entry> group = of(kind);
            if (group.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(kind.label()).append(" (").append(kind.tagline()).append("):");
            for (Entry entry : group) {
                text.append("\n- ").append(entry.key()).append(": ").append(entry.value());
            }
        }
        return text.toString();
    }

    /**
     * Applies routed entries on top of the block. A key already present is overwritten in place,
     * keeping its position; a new key is appended; a key not mentioned is left alone.
     * <p>
     * An update may move an entry between kinds — the key is the identity, the kind is an
     * attribute of it. Anything else would let one subject exist twice under two lifetimes, and
     * forgetting one of them would look like it had worked.
     *
     * @param maxEntries ceiling on the block; {@code <= 0} for none. Updates to keys already held
     *                   are always applied — the cap refuses new subjects, never corrections.
     */
    public LongTermMemory updatedWith(List<Entry> changes, int maxEntries) {
        Map<String, Entry> merged = new LinkedHashMap<>();
        for (Entry entry : entries) {
            merged.put(entry.id(), entry);
        }
        boolean changed = false;
        for (Entry entry : (changes == null) ? List.<Entry>of() : changes) {
            if (entry == null || !entry.isPresent()) {
                continue;
            }
            String existing = idOfKey(merged, entry.key());
            if (existing == null && maxEntries > 0 && merged.size() >= maxEntries) {
                continue;
            }
            if (existing != null && !existing.equals(entry.id())) {
                merged = rekeyed(merged, existing, entry);
            } else {
                merged.put(entry.id(), entry);
            }
            changed = true;
        }
        return changed ? new LongTermMemory(List.copyOf(merged.values()), revision + 1) : this;
    }

    /** The block without one entry, addressed by {@link Entry#id()}. */
    public LongTermMemory without(String entryId) {
        if (entryId == null) {
            return this;
        }
        List<Entry> kept = entries.stream().filter(entry -> !entry.id().equals(entryId)).toList();
        return (kept.size() == entries.size()) ? this : new LongTermMemory(kept, revision + 1);
    }

    /** The id currently holding a subject, whatever kind it was filed under, or null. */
    private static String idOfKey(Map<String, Entry> merged, String key) {
        for (Entry entry : merged.values()) {
            if (entry.key().equalsIgnoreCase(key)) {
                return entry.id();
            }
        }
        return null;
    }

    /** Replaces an entry whose kind changed, in place rather than at the end. */
    private static Map<String, Entry> rekeyed(Map<String, Entry> merged, String oldId, Entry entry) {
        Map<String, Entry> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> held : merged.entrySet()) {
            if (held.getKey().equals(oldId)) {
                rebuilt.put(entry.id(), entry);
            } else {
                rebuilt.put(held.getKey(), held.getValue());
            }
        }
        return rebuilt;
    }

    /**
     * Working-memory lines re-labelled for this block — the shape a closing task's promotion
     * arrives in. Takes the facts rather than the whole {@link Facts} because only the settled
     * ones are ever promoted, and handing it the block would put the decision about <em>which</em>
     * lines graduate inside a method whose name says it is only changing their type.
     */
    public static List<Entry> promoted(LongTermKind kind, List<Facts.Fact> facts) {
        List<Entry> promoted = new ArrayList<>();
        for (Facts.Fact fact : (facts == null) ? List.<Facts.Fact>of() : facts) {
            promoted.add(new Entry(kind, fact.key(), fact.value()));
        }
        return promoted;
    }
}
