package com.crispyland.agent.usage;

/**
 * What the next call is about to cost, measured against the model's context window.
 * <p>
 * The window is not "how long the prompt may be" — it has to hold the prompt <em>and</em>
 * the answer. Reserving {@code max_completion_tokens} up front is the whole point: a prompt
 * that fits with one token to spare leaves the model no room to reply, and the provider
 * rejects the call rather than truncating the input.
 * <p>
 * The breakdown is one line per memory layer, plus the things that are not memory at all:
 * <ul>
 *   <li>{@code systemTokens} — the instruction, re-sent every single call</li>
 *   <li>{@code profileTokens} — the user's declared preferences; not memory, and deliberately
 *       counted apart from it, because a profile is what somebody asked for rather than
 *       something the agent worked out, and the two fail in different ways</li>
 *   <li>{@code longTermTokens} — what is known about the user; survives this conversation</li>
 *   <li>{@code workingTokens} — the current task's block; dies with the task</li>
 *   <li>{@code summaryTokens} — short-term, compressed: the stand-in for turns already folded away</li>
 *   <li>{@code historyTokens} — short-term, verbatim: the part that grows turn by turn</li>
 *   <li>{@code inputTokens} — the new message, plus the request's framing overhead</li>
 *   <li>{@code overheadTokens} — the provider's own chat template, learned by observation</li>
 * </ul>
 * Splitting the memory lines by layer rather than reporting one "context" figure is what makes
 * the model's behaviour attributable: when an answer recalls something, the layer it came from
 * has a price attached to it, and a layer that costs tokens every turn and never changes an
 * answer is visibly not paying for itself.
 *
 * @param droppedMessages how many oldest messages the trim policy removed to make the call fit —
 *                        an overflow rescue, not a plan
 * @param replacedTokens  what the folded-away messages would still be adding to this prompt,
 *                        which is the only honest baseline to measure the saving against
 * @param calibrated      false until this model has been seen once and the overhead is real
 */
public record ContextBudget(
        String model,
        long contextWindow,
        long systemTokens,
        long profileTokens,
        long longTermTokens,
        long workingTokens,
        long summaryTokens,
        long historyTokens,
        long inputTokens,
        long overheadTokens,
        long reservedCompletionTokens,
        int droppedMessages,
        long replacedTokens,
        boolean calibrated,
        double warnAt) {

    /** Estimated {@code prompt_tokens} for the request as it will be sent. */
    public long promptTokens() {
        return countedTokens() + overheadTokens;
    }

    /** The messages alone, before the provider's template is added — what was actually encoded. */
    public long countedTokens() {
        return systemTokens + profileTokens + longTermTokens + workingTokens + summaryTokens
                + historyTokens + inputTokens;
    }

    /**
     * Everything the three memory layers cost on this one call.
     * <p>
     * The profile is not in here even though it sits beside them in the prompt. Memory is what
     * this figure is for — it exists so a layer that costs tokens every turn and never changes an
     * answer is visibly not paying for itself, and a profile is not up for that judgement: the
     * user asked for it, so its cost is theirs to decide on, not the agent's to justify.
     */
    public long memoryTokens() {
        return longTermTokens + workingTokens + summaryTokens + historyTokens;
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

    public boolean hasProfile() {
        return profileTokens > 0;
    }

    public boolean hasLongTerm() {
        return longTermTokens > 0;
    }

    public boolean hasWorking() {
        return workingTokens > 0;
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

    public int profilePercent() {
        return percentOfWindow(profileTokens);
    }

    public int longTermPercent() {
        return percentOfWindow(longTermTokens);
    }

    public int workingPercent() {
        return percentOfWindow(workingTokens);
    }

    public int summaryPercent() {
        return percentOfWindow(summaryTokens);
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
