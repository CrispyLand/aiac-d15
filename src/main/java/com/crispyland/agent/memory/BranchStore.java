package com.crispyland.agent.memory;

import java.util.List;

/**
 * Which lines a conversation has been split into, and which one the visitor is looking at.
 * Metadata only — the messages themselves stay in the {@link ConversationStore}, one key per
 * branch. Kept separate for the same reason a git ref is not the objects it points at.
 */
public interface BranchStore {

    /** Trunk first, then forks in the order they were made. Never empty. */
    List<Branch> all(String conversationId);

    /** The branch id currently being continued; {@link Branch#TRUNK} until something is forked. */
    String active(String conversationId);

    /** Ignores an unknown id rather than failing: a stale tab must not be able to 500 the page. */
    void activate(String conversationId, String branchId);

    /**
     * Records a branch and makes it active. The branch arrives already minted because only the
     * caller knows how many messages the copy actually took — this store never touches a
     * transcript, and is not the place to find out.
     */
    void add(String conversationId, Branch branch);

    void clear(String conversationId);
}
