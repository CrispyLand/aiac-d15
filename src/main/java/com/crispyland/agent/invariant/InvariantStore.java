package com.crispyland.agent.invariant;

/**
 * Where standing invariants live. Keyed by <em>visitor</em>, like long-term memory and unlike a
 * conversation — a constraint that expired when you opened a new chat would not be a constraint.
 * <p>
 * Only {@link InvariantScope#GLOBAL} rules are kept here. Task-scoped ones live beside task state,
 * in the conversation file, so that they are discarded by the same act that discards the task. The
 * split is the repo's usual one: two lifetimes, two files, and {@code ls} can verify the claim.
 * <p>
 * Note what this interface does <em>not</em> have: anything the extractor could call. Declaring an
 * invariant is a human act. A model that can mint its own constraints can mint the constraint that
 * permits what it wanted to do, which makes the whole mechanism decorative.
 */
public interface InvariantStore {

    /** The rules for a visitor. Never null; {@link Invariants#EMPTY} when none were declared. */
    Invariants held(String visitorId);

    /**
     * Declares a new rule or amends one held under the same id, and returns the set as it stands.
     * A blank id is assigned the next free one.
     */
    Invariants declare(String visitorId, Invariant invariant);

    /** Stops a rule binding, keeping it and the reason. Unknown ids are not an error. */
    Invariants retire(String visitorId, String id, String reason);

    /** Puts a retired rule back in force. */
    Invariants restore(String visitorId, String id);

    /** Drops every rule held for a visitor — part of forgetting them entirely. */
    void clear(String visitorId);
}
