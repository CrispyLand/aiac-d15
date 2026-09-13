package com.crispyland.agent;

import com.crispyland.agent.usage.ContextBudget;

/**
 * The next call cannot fit in the model's context window, and the agent refused to spend it.
 * Carries the budget so the caller can show the arithmetic rather than a bare message.
 */
public class ContextOverflowException extends AgentException {

    private final transient ContextBudget budget;

    public ContextOverflowException(ContextBudget budget) {
        super(describe(budget));
        this.budget = budget;
    }

    public ContextBudget budget() {
        return budget;
    }

    private static String describe(ContextBudget budget) {
        return ("Context window exceeded for %s: %,d prompt + %,d reserved for the reply = %,d, "
                + "but the window holds %,d (over by %,d). "
                + "The prompt breaks down as %,d system + %,d summary + %,d history "
                + "+ %,d new message + %,d chat template — "
                + "start a new chat, shorten the system prompt, or lower max completion tokens.")
                .formatted(budget.model(),
                        budget.promptTokens(),
                        budget.reservedCompletionTokens(),
                        budget.projectedTokens(),
                        budget.contextWindow(),
                        -budget.remainingTokens(),
                        budget.systemTokens(),
                        budget.summaryTokens(),
                        budget.historyTokens(),
                        budget.inputTokens(),
                        budget.overheadTokens());
    }
}
