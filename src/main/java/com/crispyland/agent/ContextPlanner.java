package com.crispyland.agent;

import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.Summary;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.OverflowPolicy;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenCounter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides what actually goes into the next request, and prices it first.
 * <p>
 * The message window in {@code agent.memory.max-messages} bounds the dialogue in
 * <em>messages</em>, which is not the unit the model charges in: twenty one-word turns and
 * twenty pasted stack traces are the same window and wildly different prompts. This is the
 * second window, measured in the unit that can actually overflow.
 * <p>
 * Both windows are subtractive — they answer a prompt that is too long by sending less of it.
 * The summary is the third option: the compressed head of the dialogue arrives as one bounded
 * message standing in for the turns that are no longer sent, so the prompt shrinks without the
 * conversation losing what happened in them.
 */
public class ContextPlanner {

    /** The message array to send, and what it is estimated to cost. */
    public record ContextPlan(List<Message> messages, ContextBudget budget) {

        public ContextPlan {
            messages = List.copyOf(messages);
        }
    }

    private final TokenCounter counter;
    private final TemplateOverhead overhead;
    private final Map<String, Integer> contextWindows;
    private final int defaultWindow;
    private final OverflowPolicy policy;
    private final double warnAt;

    public ContextPlanner(TokenCounter counter,
                          TemplateOverhead overhead,
                          Map<String, Integer> contextWindows,
                          int defaultWindow,
                          OverflowPolicy policy,
                          double warnAt) {
        this.counter = counter;
        this.overhead = overhead;
        this.contextWindows = (contextWindows == null) ? Map.of() : Map.copyOf(contextWindows);
        this.defaultWindow = defaultWindow;
        this.policy = (policy == null) ? OverflowPolicy.FAIL : policy;
        this.warnAt = warnAt;
    }

    /**
     * Prices the system prompt, the summary, the replayed history and the new message against
     * the model's window, applies the overflow policy, and returns the message array to send.
     *
     * @param summary stands in for whatever has been compressed out of {@code history}; it is
     *                sent instead of those messages, not in addition to them
     * @throws ContextOverflowException under {@link OverflowPolicy#FAIL}, or under
     *         {@link OverflowPolicy#TRIM} when even an empty history does not fit
     */
    public ContextPlan plan(AgentConfig config, Summary summary, List<Message> history, String input) {
        // The system prompt is re-applied fresh each turn rather than stored, so editing it
        // on the page takes effect immediately — and is re-paid for on every single call.
        Message system = hasSystemPrompt(config) ? Message.system(config.systemPrompt()) : null;
        Message recall = recallMessage(summary);
        Message userMessage = Message.user(input);

        long systemTokens = counter.count(system);
        long summaryTokens = counter.count(recall);
        // The once-per-request reply priming rides along with the new message so that the
        // three segments add up exactly to the estimated prompt.
        long inputTokens = counter.count(userMessage) + BpeTokenCounter.TOKENS_PER_REPLY;
        long reserved = reserved(config);
        long window = windowFor(config.model());
        long templateTokens = overhead.forModel(config.model());

        List<Message> replayed = (history == null) ? List.of() : history;
        long[] perMessage = new long[replayed.size()];
        long historyTokens = 0L;
        for (int i = 0; i < replayed.size(); i++) {
            perMessage[i] = counter.count(replayed.get(i));
            historyTokens += perMessage[i];
        }

        int dropped = 0;
        if (policy == OverflowPolicy.TRIM) {
            long fixed = systemTokens + summaryTokens + inputTokens + templateTokens + reserved;
            while (dropped < replayed.size() && fixed + historyTokens > window) {
                historyTokens -= perMessage[dropped];
                dropped++;
            }
            // Leave the window opening on a user message; a history that starts mid-turn
            // reads as though the assistant spoke first.
            if (dropped > 0 && dropped < replayed.size() && !replayed.get(dropped).isUser()) {
                historyTokens -= perMessage[dropped];
                dropped++;
            }
            replayed = replayed.subList(dropped, replayed.size());
        }

        ContextBudget budget = new ContextBudget(config.model(), window, systemTokens,
                summaryTokens, historyTokens, inputTokens, templateTokens, reserved, dropped,
                replacedTokens(summary), overhead.calibrated(config.model()), warnAt);

        // OFF deliberately sends anyway, so the provider's own rejection can be observed.
        if (budget.overflowing() && policy != OverflowPolicy.OFF) {
            throw new ContextOverflowException(budget);
        }

        List<Message> messages = new ArrayList<>(replayed.size() + 3);
        if (system != null) {
            messages.add(system);
        }
        // Before the retained turns, so the model reads the dialogue in chronological order:
        // what it has forgotten, then what it still has verbatim.
        if (recall != null) {
            messages.add(recall);
        }
        messages.addAll(replayed);
        messages.add(userMessage);
        return new ContextPlan(messages, budget);
    }

    /**
     * Prices a dialogue with no new message — what the page shows before anything is typed,
     * so the window filling up is visible turn by turn rather than only at the moment it breaks.
     */
    public ContextBudget budget(AgentConfig config, Summary summary, List<Message> history) {
        long systemTokens = hasSystemPrompt(config) ? counter.count(Message.system(config.systemPrompt())) : 0L;
        long historyTokens = 0L;
        if (history != null) {
            for (Message message : history) {
                historyTokens += counter.count(message);
            }
        }
        return new ContextBudget(config.model(), windowFor(config.model()), systemTokens,
                counter.count(recallMessage(summary)), historyTokens, 0L,
                overhead.forModel(config.model()), reserved(config), 0, replacedTokens(summary),
                overhead.calibrated(config.model()), warnAt);
    }

    /**
     * The summary rides as its own system message rather than being glued onto the system
     * prompt: the prompt is an instruction the user edits, the summary is recalled state, and
     * the label is what stops the model from reading stale notes as a fresh directive.
     */
    private static Message recallMessage(Summary summary) {
        if (summary == null || !summary.isPresent()) {
            return null;
        }
        return Message.system("Notes on the earlier part of this conversation, which is no "
                + "longer included verbatim. Treat them as established fact:\n" + summary.text());
    }

    private static long replacedTokens(Summary summary) {
        return (summary == null) ? 0L : summary.replacedTokens();
    }

    public OverflowPolicy policy() {
        return policy;
    }

    private long reserved(AgentConfig config) {
        Integer max = config.maxCompletionTokens();
        return (max == null || max <= 0) ? 0L : max;
    }

    private long windowFor(String model) {
        if (model == null) {
            return defaultWindow;
        }
        Integer window = contextWindows.get(model);
        if (window == null) {
            window = contextWindows.get(model.toLowerCase(Locale.ROOT));
        }
        return (window == null || window <= 0) ? defaultWindow : window;
    }

    private static boolean hasSystemPrompt(AgentConfig config) {
        return config.systemPrompt() != null && !config.systemPrompt().isBlank();
    }
}
