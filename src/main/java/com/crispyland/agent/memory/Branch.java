package com.crispyland.agent.memory;

import java.util.List;
import java.util.Locale;

/**
 * One line of a conversation. Branching is the odd one out among the strategies on the page:
 * the other three are policies for <em>assembling a prompt</em> out of one stored dialogue,
 * while this one changes where the dialogue is <em>stored</em>. That is why it is not a fourth
 * tab but something that composes with all three — a branch is the same conversation under a
 * different key, so windowing, facts, summaries, persistence and the budget all work inside it
 * without knowing it exists.
 *
 * @param parentId the branch this was forked out of; {@code null} on the trunk
 * @param forkedAt how many messages were carried over — the checkpoint, in messages
 */
public record Branch(String id, String name, String parentId, int forkedAt) {

    public static final String TRUNK = "main";

    /** Every conversation has one whether the user ever forks or not. */
    public static final Branch MAIN = new Branch(TRUNK, "main", null, 0);

    public boolean isTrunk() {
        return TRUNK.equals(id);
    }

    /**
     * The storage key a branch's messages live under. The trunk deliberately keeps the bare
     * conversation id: a visitor who never forks stores exactly what they stored before this
     * feature existed, so branching costs nothing until it is used.
     */
    public static String key(String conversationId, String branchId) {
        return (branchId == null || TRUNK.equals(branchId))
                ? conversationId
                : conversationId + "/" + branchId;
    }

    /**
     * Mints a branch that cannot collide with one already on the conversation. The id is derived
     * from the typed name so the storage key stays readable in the JSON file, and de-duplicated
     * with a suffix so forking "option b" twice does not silently continue the first one.
     */
    public static Branch forked(List<Branch> existing, String name, String parentId, int forkedAt) {
        String base = sanitized(name);
        String candidate = base.isEmpty() ? "branch" : base;
        int attempt = 1;
        while (taken(existing, candidate)) {
            candidate = (base.isEmpty() ? "branch" : base) + "-" + (++attempt);
        }
        String label = (name == null || name.isBlank()) ? candidate : name.strip();
        return new Branch(candidate, label, parentId, Math.max(forkedAt, 0));
    }

    private static boolean taken(List<Branch> existing, String id) {
        return TRUNK.equals(id) || existing.stream().anyMatch(branch -> branch.id().equals(id));
    }

    /** Ids are minted, not typed, but they end up in a file path-like key — so keep them tame. */
    public static String sanitized(String value) {
        String cleaned = (value == null) ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
        return cleaned.isEmpty() ? "" : cleaned.substring(0, Math.min(cleaned.length(), 24));
    }
}
