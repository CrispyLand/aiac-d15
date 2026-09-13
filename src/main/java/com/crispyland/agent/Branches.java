package com.crispyland.agent;

import com.crispyland.agent.memory.Branch;
import com.crispyland.agent.memory.BranchStore;
import com.crispyland.agent.memory.ConversationStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Branching, in one place: the refs live in a {@link BranchStore}, the messages live in the
 * {@link ConversationStore} under one key per branch, and forking is a copy from one key to
 * another. Nothing else in the agent knows branches exist — {@link Agent} is handed a key and
 * answers turns, which is exactly why summaries, facts, windowing and the budget all work
 * inside a branch without a line of code each.
 * <p>
 * The comparison this makes possible is the point: two branches off the same checkpoint differ
 * only in what was said after it, so any difference in the answers is the conversation's doing
 * rather than the model's temperature.
 */
public class Branches {

    private static final Logger log = LoggerFactory.getLogger(Branches.class);

    private final BranchStore branches;
    private final ConversationStore conversations;

    public Branches(BranchStore branches, ConversationStore conversations) {
        this.branches = branches;
        this.conversations = conversations;
    }

    /** Trunk first, then forks in the order they were made. Never empty. */
    public List<Branch> all(String conversationId) {
        return branches.all(conversationId);
    }

    public String active(String conversationId) {
        return branches.active(conversationId);
    }

    /** The id the agent should be given for this visitor's current line of the conversation. */
    public String activeKey(String conversationId) {
        return Branch.key(conversationId, branches.active(conversationId));
    }

    /**
     * Checkpoints the active branch at {@code atMessage} and continues on a copy. The parent is
     * left exactly as it was, so forking twice from the same point gives two independent
     * continuations of one conversation rather than a conversation and its edit.
     */
    public Branch fork(String conversationId, String name, int atMessage) {
        String parentKey = activeKey(conversationId);
        Branch minted = Branch.forked(branches.all(conversationId), name,
                branches.active(conversationId), atMessage);
        // Record what the copy actually took rather than what was asked for: "from msg 4" has to
        // mean the branch really starts at four messages, or the badge is decoration.
        int copied = conversations.copy(parentKey, Branch.key(conversationId, minted.id()), atMessage);
        Branch created = new Branch(minted.id(), minted.name(), minted.parentId(), copied);
        branches.add(conversationId, created);
        log.info("Forked '{}' off '{}' — {} message(s) carried over",
                created.id(), created.parentId(), copied);
        return created;
    }

    public void switchTo(String conversationId, String branchId) {
        branches.activate(conversationId, branchId);
    }

    /** A reset is of the whole conversation, branches included — otherwise they would be orphaned. */
    public void reset(String conversationId) {
        for (Branch branch : branches.all(conversationId)) {
            conversations.clear(Branch.key(conversationId, branch.id()));
        }
        branches.clear(conversationId);
    }
}
