package com.crispyland.agent.usage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns the fixed cost a provider's chat template adds on top of the messages themselves.
 * <p>
 * Counting the message text is the easy half. The provider then wraps it in a template the
 * caller never sees: {@code gpt-oss} uses the harmony format, which prepends model identity,
 * a knowledge cutoff, the reasoning level and channel instructions to every single request.
 * Measured against Groq, that is roughly sixty tokens that no amount of encoding the user's
 * own text will ever reveal — an estimator that ignores it under-counts by more than half on
 * short prompts, which is the wrong direction for a budget to be wrong in.
 * <p>
 * Rather than hardcode one provider's template — which would be wrong the day they revise it —
 * the offset is measured: every response reports the true {@code prompt_tokens}, so the gap
 * between that and the local count <em>is</em> the overhead, and it can simply be remembered.
 * Smoothed rather than replaced outright, because the gap also absorbs ordinary tokenizer
 * drift on the message text, which varies per turn while the template does not.
 */
public class TemplateOverhead {

    /** Weight given to the newest observation. Converges in a turn or two, ignores outliers. */
    private static final double SMOOTHING = 0.5;

    private final Map<String, Double> observed = new ConcurrentHashMap<>();

    /**
     * Best current guess at the per-request template cost for a model; 0 until one is seen.
     * <p>
     * Never negative. A template can only add tokens, so a provider reporting fewer than were
     * counted locally means the local count is the high one — and a budget that must not be
     * exceeded should keep the pessimistic figure rather than talk itself down.
     */
    public long forModel(String model) {
        Double overhead = (model == null) ? null : observed.get(model);
        return (overhead == null) ? 0L : Math.max(0L, Math.round(overhead));
    }

    /** Whether this model has been seen at least once, so the estimate can be trusted. */
    public boolean calibrated(String model) {
        return model != null && observed.containsKey(model);
    }

    /**
     * Folds one turn's ground truth into the running offset.
     *
     * @param countedTokens the local count of the messages alone, with no overhead applied
     * @param actualPromptTokens what the provider actually billed as {@code prompt_tokens}
     */
    public void observe(String model, long countedTokens, long actualPromptTokens) {
        if (model == null || actualPromptTokens <= 0) {
            return;
        }
        double delta = actualPromptTokens - countedTokens;
        observed.merge(model, delta, (previous, fresh) -> previous + SMOOTHING * (fresh - previous));
    }
}
