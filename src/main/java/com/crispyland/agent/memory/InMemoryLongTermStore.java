package com.crispyland.agent.memory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-term memory that is not, in fact, long-term: it dies with the process.
 * <p>
 * Useful for tests and for a deployment that wants the layer model without keeping anything about
 * anyone on disk. The layer still behaves correctly relative to the others — it survives a reset
 * and outlives every conversation, which is what {@link MemoryLayer#LONG_TERM} actually promises.
 */
public class InMemoryLongTermStore implements LongTermStore {

    private final Map<String, LongTermMemory> visitors = new ConcurrentHashMap<>();
    private final int maxEntries;

    /** @param maxEntries ceiling per visitor; {@code <= 0} for none */
    public InMemoryLongTermStore(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    @Override
    public LongTermMemory recall(String visitorId) {
        return visitors.getOrDefault(visitorId, LongTermMemory.EMPTY);
    }

    @Override
    public LongTermMemory remember(String visitorId, List<LongTermMemory.Entry> entries) {
        return visitors.compute(visitorId, (key, held) ->
                (held == null ? LongTermMemory.EMPTY : held).updatedWith(entries, maxEntries));
    }

    @Override
    public LongTermMemory forget(String visitorId, String entryId) {
        return visitors.compute(visitorId, (key, held) ->
                (held == null ? LongTermMemory.EMPTY : held).without(entryId));
    }

    @Override
    public void forgetAll(String visitorId) {
        visitors.remove(visitorId);
    }
}
