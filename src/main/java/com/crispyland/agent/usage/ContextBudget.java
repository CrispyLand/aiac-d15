package com.crispyland.agent.usage;

/**
 * What the next call is about to cost, measured against the model's context window.
 * <p>
 * The window is not "how long the prompt may be" — it has to hold the prompt <em>and</em>
 * the answer. Reserving {@code max_completion_tokens} up front is the whole point: a prompt
 * that fits with one token to spare leaves the model no room to reply, and the provider
 * rejects the call rather than truncating the input.
 * <p>
 * Four segments, which is exactly the breakdown the dialogue grows in:
 * <ul>
 *   <li>{@code systemTokens} — fixed per turn, re-sent every single call</li>
 *   <li>{@code historyTokens} — the part that grows; every past turn is re-sent in full</li>
 *   <li>{@code inputTokens} — the new message, plus the request's framing overhead</li>
 *   <li>{@code overheadTokens} — the provider's own chat template, learned by observation</li>
 * </ul>
 *
 * @param droppedMessages how many oldest messages the trim policy removed to make it fit
 * @param calibrated      false until this model has been seen once and the overhead is real
 */
public record ContextBudget(
        String model,
        long contextWindow,
        long systemTokens,
        long historyTokens,
        long inputTokens,
        long overheadTokens,
        long reservedCompletionTokens,
        int droppedMessages,
        boolean calibrated,
        double warnAt) {

    /** Estimated {@code prompt_tokens} for the request as it will be sent. */
    public long promptTokens() {
        return systemTokens + historyTokens + inputTokens + overheadTokens;
    }

    /** The messages alone, before the provider's template is added — what was actually encoded. */
    public long countedTokens() {
        return systemTokens + historyTokens + inputTokens;
    }

    /** Worst case for the whole call: the prompt plus a reply that runs to its limit. */
    public long projectedTokens() {
        return promptTokens() + reservedCompletionTokens;
    }

    /** Headroom left in the window; negative once the call cannot fit. */
    public long remainingTokens() {
        return contextWindow - projectedTokens();
    }

    public boolean overflowing() {
        return contextWindow > 0 && projectedTokens() > contextWindow;
    }

    public boolean warning() {
        return !overflowing() && contextWindow > 0 && usedRatio() >= warnAt;
    }

    public double usedRatio() {
        return (contextWindow <= 0) ? 0d : (double) projectedTokens() / contextWindow;
    }

    public int usedPercent() {
        return (int) Math.round(usedRatio() * 100);
    }

    /** {@code ok} | {@code warn} | {@code over} — drives the colour of the bar. */
    public String status() {
        if (overflowing()) {
            return "over";
        }
        return warning() ? "warn" : "ok";
    }

    public boolean trimmed() {
        return droppedMessages > 0;
    }

    /** Width of each segment as a percentage of the window, for rendering the bar. */
    public int percentOfWindow(long tokens) {
        if (contextWindow <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(100d * tokens / contextWindow));
    }

    public int systemPercent() {
        return percentOfWindow(systemTokens);
    }

    public int historyPercent() {
        return percentOfWindow(historyTokens);
    }

    public int inputPercent() {
        return percentOfWindow(inputTokens);
    }

    public int overheadPercent() {
        return percentOfWindow(overheadTokens);
    }

    public int reservedPercent() {
        return percentOfWindow(reservedCompletionTokens);
    }
}
