package com.crispyland.agent.invariant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invariants that die with the process. For tests, and for a deployment that wants the mechanism
 * without keeping anyone's commitments on disk.
 */
public class InMemoryInvariantStore implements InvariantStore {

    private final Map<String, Invariants> visitors = new ConcurrentHashMap<>();

    @Override
    public Invariants held(String visitorId) {
        return visitors.getOrDefault(visitorId, Invariants.EMPTY);
    }

    @Override
    public Invariants declare(String visitorId, Invariant invariant) {
        return visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).with(invariant));
    }

    @Override
    public Invariants retire(String visitorId, String id, String reason) {
        return visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).retire(id, reason));
    }

    @Override
    public Invariants restore(String visitorId, String id) {
        return visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).restore(id));
    }

    @Override
    public void clear(String visitorId) {
        visitors.remove(visitorId);
    }
}
