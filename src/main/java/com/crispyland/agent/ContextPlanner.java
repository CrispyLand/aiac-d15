package com.crispyland.agent;

import com.crispyland.agent.memory.Facts;
import com.crispyland.agent.memory.LongTermMemory;
import com.crispyland.agent.memory.MemoryState;
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
 * Turns the memory layers into the next request, and prices each layer before sending it.
 * <p>
 * This is the only place that knows how a prompt is laid out, so the ordering decisions live
 * here. Layers are emitted most-durable first and the user's new message last, which matters
 * because attention is not uniform: the tail of a prompt carries disproportionate weight, so the
 * question itself goes last and the standing context goes above it. The summary sits directly
 * before the verbatim tail so the transcript still reads in chronological order — what has been
 * forgotten, then what is still remembered word for word.
 * <p>
 * Each layer is a separate {@code system} message rather than being glued onto the system
 * prompt. The system prompt is an instruction the user edits; recalled memory is state. Labelling
 * them separately is what stops the model from reading last week's notes as a fresh directive,
 * and it is what lets the budget attribute tokens to a layer instead of to "context".
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
     * Prices the system prompt, every memory layer, and the new message against the model's
     * window, applies the overflow policy, and returns the array to send.
     * <p>
     * Note what is <em>not</em> here any more: a read-time window over the transcript. The
     * short-term buffer is bounded where it is written instead, by folding whatever falls out
     * of it into the summary. Cutting in both places is how messages go missing — anything
     * dropped by a read-time window but not yet folded is covered by neither, and nothing says so.
     *
     * @throws ContextOverflowException under {@link OverflowPolicy#FAIL}, or under
     *         {@link OverflowPolicy#TRIM} when even an empty history does not fit
     */
    public ContextPlan plan(AgentConfig config, MemoryState memory, String input) {
        MemoryState state = (memory == null) ? MemoryState.EMPTY : memory;

        // The system prompt is re-applied fresh each turn rather than stored, so editing it
        // on the page takes effect immediately — and is re-paid for on every single call.
        Message system = hasSystemPrompt(config) ? Message.system(config.systemPrompt()) : null;
        Message known = longTermMessage(state.longTerm());
        Message workingNote = workingMessage(state.working());
        Message recall = recallMessage(state.summary());
        Message userMessage = Message.user(input);

        long systemTokens = counter.count(system);
        long longTermTokens = counter.count(known);
        long workingTokens = counter.count(workingNote);
        long summaryTokens = counter.count(recall);
        // The once-per-request reply priming rides along with the new message so that the
        // segments add up exactly to the estimated prompt.
        long inputTokens = counter.count(userMessage) + BpeTokenCounter.TOKENS_PER_REPLY;
        long reserved = reserved(config);
        long window = windowFor(config.model());
        long templateTokens = overhead.forModel(config.model());

        List<Message> replayed = state.recent();
        long[] perMessage = new long[replayed.size()];
        long historyTokens = 0L;
        for (int i = 0; i < replayed.size(); i++) {
            perMessage[i] = counter.count(replayed.get(i));
            historyTokens += perMessage[i];
        }

        int dropped = 0;
        if (policy == OverflowPolicy.TRIM) {
            long fixed = systemTokens + longTermTokens + workingTokens + summaryTokens
                    + inputTokens + templateTokens + reserved;
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
                longTermTokens, workingTokens, summaryTokens, historyTokens, inputTokens,
                templateTokens, reserved, dropped, state.summary().replacedTokens(),
                overhead.calibrated(config.model()), warnAt);

        // OFF deliberately sends anyway, so the provider's own rejection can be observed.
        if (budget.overflowing() && policy != OverflowPolicy.OFF) {
            throw new ContextOverflowException(budget);
        }

        List<Message> messages = new ArrayList<>(replayed.size() + 5);
        if (system != null) {
            messages.add(system);
        }
        if (known != null) {
            messages.add(known);
        }
        if (workingNote != null) {
            messages.add(workingNote);
        }
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
    public ContextBudget budget(AgentConfig config, MemoryState memory) {
        MemoryState state = (memory == null) ? MemoryState.EMPTY : memory;

        long systemTokens = hasSystemPrompt(config)
                ? counter.count(Message.system(config.systemPrompt())) : 0L;
        long historyTokens = 0L;
        for (Message message : state.recent()) {
            historyTokens += counter.count(message);
        }

        return new ContextBudget(config.model(), windowFor(config.model()), systemTokens,
                counter.count(longTermMessage(state.longTerm())),
                counter.count(workingMessage(state.working())),
                counter.count(recallMessage(state.summary())), historyTokens, 0L,
                overhead.forModel(config.model()), reserved(config), 0,
                state.summary().replacedTokens(),
                overhead.calibrated(config.model()), warnAt);
    }

    /**
     * Long-term. Sent first of the three because it is the least likely to be wrong about
     * <em>now</em> and the most likely to be wrong about the task: it is background, not brief.
     * <p>
     * The wording says "unless this conversation says otherwise" deliberately. Everything below
     * this block is newer than it by construction, so on a conflict the newer layer has to win —
     * without that clause a stale profile line argues with a correction the user made a minute ago
     * and sometimes wins, which is precisely how long-lived memory turns from a feature into a bug.
     */
    private static Message longTermMessage(LongTermMemory longTerm) {
        if (longTerm == null || !longTerm.isPresent()) {
            return null;
        }
        return Message.system("What you remember about this person from earlier conversations. "
                + "Use it without being asked, but let anything in this conversation override it:\n"
                + longTerm.render());
    }

    /**
     * Short-term, compressed. Labelled as notes rather than as instruction so the model treats
     * it as a record of what happened, which is all a summary can honestly claim to be.
     */
    private static Message recallMessage(Summary summary) {
        if (summary == null || !summary.isPresent()) {
            return null;
        }
        return Message.system("Notes on the earlier part of this conversation, which is no "
                + "longer included verbatim. Treat them as established fact:\n" + summary.text());
    }

    /**
     * Working memory. The wording is stronger than the summary's because the point of a keyed
     * block is that it outranks the transcript: when the last six messages and the block
     * disagree, the block is the one that was maintained deliberately.
     */
    private static Message workingMessage(Facts working) {
        if (working == null || !working.isPresent()) {
            return null;
        }
        return Message.system("Established facts about the task in hand, maintained across "
                + "messages that are no longer included. Treat them as current and authoritative:\n"
                + working.render());
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
