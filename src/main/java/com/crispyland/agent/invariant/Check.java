package com.crispyland.agent.invariant;

import java.util.Locale;

/**
 * How an invariant is enforced — and, just as importantly, what enforcing it costs.
 * <p>
 * Not everything can be checked in Java, and checking everything with a model is expensive enough
 * to cancel out the point of having a model at all. So the mode is chosen per rule by the person
 * writing it, and the cheap modes exist to keep the expensive one rare:
 * <ul>
 *   <li>{@link #FORBID} — free. No call, no tokens, answers in microseconds.</li>
 *   <li>{@link #WATCH} — free on every turn that does not mention a watched term, and one extra
 *       call on the turns that do. This is the mode most rules should use.</li>
 *   <li>{@link #MODEL} — one extra call on <em>every</em> turn. Correct for rules with nothing
 *       literal to key on, and the reason to reach for {@link #WATCH} first.</li>
 * </ul>
 * <p>
 * The honest ceiling, worth stating rather than hiding: the strongest way to enforce "only Koin for
 * dependency injection" is a lint rule in CI, not a chat guard. This agent has no tools and cannot
 * run one. It can refuse to <em>propose</em> a breach; it cannot prevent one being written. A fourth
 * mode that shells out to a real checker is the obvious shape of that, once tools exist.
 */
public enum Check {

    /**
     * A watched term appearing in the answer <em>is</em> the breach. Decided in Java, so it is
     * exact, instant and costs nothing.
     * <p>
     * Only correct when the term cannot legitimately appear. "Never transliterate English technical
     * terms" is a good fit; "do not propose Redis" is not, because
     * <em>"Redis would be wrong here"</em> is a sentence that honours the rule while mentioning it.
     * For anything that can be named without being proposed, use {@link #WATCH}.
     */
    FORBID("forbid", "a watched term is itself the breach"),

    /**
     * A watched term only makes the answer <em>worth checking</em>. The guard call decides whether
     * it is actually a breach; a turn that mentions nothing watched skips the call entirely.
     * <p>
     * This is the setting that makes the design affordable: the cheap check is a filter for the
     * expensive one rather than a verdict, so the average turn pays nothing and only the suspicious
     * ones pay for judgement.
     */
    WATCH("watch", "a watched term triggers a check, which decides"),

    /**
     * Ask on every turn. For rules with no literal to key on — "do not propose a design that
     * couples the domain layer to the transport layer" has no word that gives it away.
     * <p>
     * Also the fallback for a rule declared as {@link #FORBID} or {@link #WATCH} with no terms,
     * because an unenforced rule is worse than an expensive one. That downgrade is deliberate and
     * is logged, since it silently makes every turn cost an extra call.
     */
    MODEL("model", "asked on every turn");

    private final String id;
    private final String what;

    Check(String id, String what) {
        this.id = id;
        this.what = what;
    }

    public String id() {
        return id;
    }

    public String what() {
        return what;
    }

    /** True when this mode does nothing without watched terms to key on. */
    public boolean needsTerms() {
        return this == FORBID || this == WATCH;
    }

    /** True when a term hit settles it in Java, with no call. */
    public boolean decidesAlone() {
        return this == FORBID;
    }

    /** The mode named, or {@code null} for anything unrecognised. */
    public static Check from(String check) {
        if (check == null) {
            return null;
        }
        String normalized = check.strip().toLowerCase(Locale.ROOT);
        for (Check candidate : values()) {
            if (candidate.id.equals(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
