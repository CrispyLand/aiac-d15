package com.crispyland.agent.invariant;

import java.util.Locale;

/**
 * How far an invariant reaches, and therefore how long it lives.
 * <p>
 * Named {@code InvariantScope} rather than {@code Scope} because
 * {@link com.crispyland.agent.memory.MemoryScope} already means something else here — it addresses
 * <em>whose</em> memory, while this says <em>how long a rule binds</em>.
 * <p>
 * The split follows the repo's existing lifetime discipline rather than inventing a new one: a
 * {@link #GLOBAL} rule is stored beside long-term memory and outlives every task, a {@link #TASK}
 * rule is stored beside task state and dies with it. Keeping them in one enum but two files is the
 * same move as long-term versus working memory — one idea, two lifetimes, two places on disk.
 */
public enum InvariantScope {

    /**
     * Binds every conversation this visitor has, on every branch. Survives closing a task and
     * resetting a chat; only retiring it stops it applying.
     */
    GLOBAL("global", "binds everything, until retired"),

    /**
     * Binds only the task in hand and is discarded with it. For a constraint that is true of this
     * job and would be wrong as a standing rule — "not touching the billing module this sprint".
     */
    TASK("task", "binds this job only, and dies with it");

    private final String id;
    private final String what;

    InvariantScope(String id, String what) {
        this.id = id;
        this.what = what;
    }

    public String id() {
        return id;
    }

    public String what() {
        return what;
    }

    public boolean isGlobal() {
        return this == GLOBAL;
    }

    /** The scope named, or {@code null} for anything unrecognised. */
    public static InvariantScope from(String scope) {
        if (scope == null) {
            return null;
        }
        String normalized = scope.strip().toLowerCase(Locale.ROOT);
        for (InvariantScope candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
