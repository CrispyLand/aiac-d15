package com.crispyland.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Default branch store: refs in memory, forgotten on shutdown. */
public class InMemoryBranchStore implements BranchStore {

    final Map<String, List<Branch>> branches = new ConcurrentHashMap<>();
    final Map<String, String> active = new ConcurrentHashMap<>();

    @Override
    public List<Branch> all(String conversationId) {
        List<Branch> stored = branches.get(conversationId);
        return (stored == null || stored.isEmpty()) ? List.of(Branch.MAIN) : List.copyOf(stored);
    }

    @Override
    public String active(String conversationId) {
        String branchId = active.get(conversationId);
        return (branchId == null) ? Branch.TRUNK : branchId;
    }

    @Override
    public void activate(String conversationId, String branchId) {
        if (all(conversationId).stream().anyMatch(branch -> branch.id().equals(branchId))) {
            active.put(conversationId, branchId);
            changed();
        }
    }

    @Override
    public void add(String conversationId, Branch branch) {
        List<Branch> existing = new ArrayList<>(all(conversationId));
        existing.add(branch);
        branches.put(conversationId, existing);
        active.put(conversationId, branch.id());
        changed();
    }

    @Override
    public void clear(String conversationId) {
        branches.remove(conversationId);
        active.remove(conversationId);
        changed();
    }

    /** Hook for stores that also have to write the refs down somewhere. */
    void changed() {
        // nothing to do when the refs only ever live in this process
    }
}
