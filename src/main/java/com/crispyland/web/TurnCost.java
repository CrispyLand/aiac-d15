package com.crispyland.web;

import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.MessageStats;
import java.util.ArrayList;
import java.util.List;

/**
 * One turn's cost, and what the dialogue had cost by the end of it.
 * <p>
 * Purely a view over the transcript: every number here was already recorded per message when
 * the turn happened, so the growth curve survives a restart along with the dialogue and needs
 * nothing kept in memory to draw it.
 * <p>
 * The interesting column is {@code promptTokens}. It climbs every turn even when the messages
 * stay the same length, because the whole history is re-sent on each call — the cost of a
 * conversation is quadratic in its length, not linear, and that is the shape this table exists
 * to make visible.
 *
 * @param runningTotal cumulative tokens across the turns still retained, not for all time —
 *                     turns dropped by the message window are gone from the transcript
 * @param barPercent   width of this turn's prompt relative to the largest in the series
 */
public record TurnCost(int turn,
                       long promptTokens,
                       long completionTokens,
                       long totalTokens,
                       long runningTotal,
                       long latencyMillis,
                       String model,
                       String finishReason,
                       int barPercent) {

    public boolean truncated() {
        return "length".equals(finishReason);
    }

    /** Walks the message stack and pairs each user prompt with the reply it paid for. */
    public static List<TurnCost> series(List<Message> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }

        List<TurnCost> turns = new ArrayList<>();
        long running = 0L;
        long maxPrompt = 0L;
        long prompt = 0L;

        for (Message message : transcript) {
            MessageStats stats = message.stats();
            if (stats == null) {
                continue;
            }
            if (message.isUser()) {
                prompt = stats.promptTokens();
                continue;
            }
            // An assistant message closes the turn and carries the turn's totals.
            running += stats.totalTokens();
            maxPrompt = Math.max(maxPrompt, prompt);
            turns.add(new TurnCost(turns.size() + 1, prompt, stats.completionTokens(),
                    stats.totalTokens(), running, stats.latencyMillis(), stats.model(),
                    stats.finishReason(), 0));
            prompt = 0L;
        }

        return scaleBars(turns, maxPrompt);
    }

    private static List<TurnCost> scaleBars(List<TurnCost> turns, long maxPrompt) {
        if (maxPrompt <= 0) {
            return List.copyOf(turns);
        }
        List<TurnCost> scaled = new ArrayList<>(turns.size());
        for (TurnCost turn : turns) {
            int percent = (int) Math.max(2, Math.round(100d * turn.promptTokens() / maxPrompt));
            scaled.add(new TurnCost(turn.turn(), turn.promptTokens(), turn.completionTokens(),
                    turn.totalTokens(), turn.runningTotal(), turn.latencyMillis(), turn.model(),
                    turn.finishReason(), percent));
        }
        return List.copyOf(scaled);
    }
}
