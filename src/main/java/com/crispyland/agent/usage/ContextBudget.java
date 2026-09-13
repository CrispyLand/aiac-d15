package com.crispyland.agent.usage;

import com.crispyland.agent.ContextStrategy;

/**
 * What the next call is about to cost, measured against the model's context window.
 * <p>
 * The window is not "how long the prompt may be" — it has to hold the prompt <em>and</em>
 * the answer. Reserving {@code max_completion_tokens} up front is the whole point: a prompt
 * that fits with one token to spare leaves the model no room to reply, and the provider
 * rejects the call rather than truncating the input.
 * <p>
 * Six segments, which is exactly the breakdown the dialogue grows in:
 * <ul>
 *   <li>{@code systemTokens} — fixed per turn, re-sent every single call</li>
 *   <li>{@code summaryTokens} — the compressed stand-in for the turns no longer sent; bounded,
 *       and the only reason {@code historyTokens} stops growing</li>
 *   <li>{@code factsTokens} — the key/value block, also bounded, also a stand-in for the past</li>
 *   <li>{@code historyTokens} — the part that grows; every retained turn is re-sent in full</li>
 *   <li>{@code inputTokens} — the new message, plus the request's framing overhead</li>
 *   <li>{@code overheadTokens} — the provider's own chat template, learned by observation</li>
 * </ul>
 * Which of those are non-zero is entirely a function of {@code strategy}, which is why it is
 * recorded here: a budget is only comparable to another budget if you know how it was packed.
 *
 * @param strategy         how this prompt was assembled
 * @param droppedMessages  how many oldest messages the trim policy removed to make the call fit —
 *                         an overflow rescue, not a plan
 * @param windowedMessages how many the strategy's own window left out, which is not a failure but
 *                         the strategy working as intended
 * @param replacedTokens   what the compressed-away messages would still be adding to this prompt,
 *                         which is the only honest baseline to measure the saving against
 * @param calibrated       false until this model has been seen once and the overhead is real
 */
public record ContextBudget(
        String model,
        ContextStrategy strategy,
        long contextWindow,
        long systemTokens,
        long summaryTokens,
        long factsTokens,
        long historyTokens,
        long inputTokens,
        long overheadTokens,
        long reservedCompletionTokens,
        int droppedMessages,
        int windowedMessages,
        long replacedTokens,
        boolean calibrated,
        double warnAt) {

    /** Estimated {@code prompt_tokens} for the request as it will be sent. */
    public long promptTokens() {
        return systemTokens + summaryTokens + factsTokens + historyTokens + inputTokens + overheadTokens;
    }

    /** The messages alone, before the provider's template is added — what was actually encoded. */
    public long countedTokens() {
        return systemTokens + summaryTokens + factsTokens + historyTokens + inputTokens;
    }

    public boolean compressed() {
        return summaryTokens > 0;
    }

    /** What this same prompt would cost if the folded messages were still being replayed. */
    public long uncompressedPromptTokens() {
        return promptTokens() - summaryTokens + replacedTokens;
    }

    /**
     * The saving on this one call. Negative while the summary still costs more than the two or
     * three turns it replaced — compression is a bet that only pays off as the dialogue runs on,
     * and hiding that would be dishonest arithmetic.
     */
    public long savedTokens() {
        return replacedTokens - summaryTokens;
    }

    public int savedPercent() {
        long full = uncompressedPromptTokens();
        return (full <= 0) ? 0 : (int) Math.round(100d * savedTokens() / full);
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

    /** True when the strategy is deliberately not sending part of the transcript it still holds. */
    public boolean windowed() {
        return windowedMessages > 0;
    }

    public boolean hasFacts() {
        return factsTokens > 0;
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

    public int summaryPercent() {
        return percentOfWindow(summaryTokens);
    }

    public int factsPercent() {
        return percentOfWindow(factsTokens);
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
