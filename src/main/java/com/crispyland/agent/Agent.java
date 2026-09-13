package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.MessageStats;
import com.crispyland.agent.memory.Summary;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The box. One pipeline:
 * <pre>
 *   input policy -> merge config with defaults -> load history -> compress the backlog
 *       -> price the context -> LlmClient -> record token usage -> output policy -> judge
 *       -> append turn to history -> AgentResult
 * </pre>
 * The conversation is the agent's own state, not the web layer's: callers pass a
 * conversation id and the agent decides what history to send, how to window it, and when
 * to commit a turn. Knows nothing about HTTP servlets, Thymeleaf, or forms.
 */
@Service
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final LlmClient llmClient;
    private final InputPolicy inputPolicy;
    private final OutputPolicy outputPolicy;
    private final Judge judge;
    private final TokenUsageTracker usageTracker;
    private final ConversationStore conversations;
    private final ContextPlanner contextPlanner;
    private final HistoryCompressor compressor;
    private final TemplateOverhead templateOverhead;
    private final AgentConfig defaults;

    public Agent(LlmClient llmClient,
                 InputPolicy inputPolicy,
                 OutputPolicy outputPolicy,
                 Judge judge,
                 TokenUsageTracker usageTracker,
                 ConversationStore conversations,
                 ContextPlanner contextPlanner,
                 HistoryCompressor compressor,
                 TemplateOverhead templateOverhead,
                 AgentProperties properties) {
        this.llmClient = llmClient;
        this.inputPolicy = inputPolicy;
        this.outputPolicy = outputPolicy;
        this.judge = judge;
        this.usageTracker = usageTracker;
        this.conversations = conversations;
        this.contextPlanner = contextPlanner;
        this.compressor = compressor;
        this.templateOverhead = templateOverhead;
        this.defaults = properties.defaultConfig();
    }

    /**
     * Handles one turn of a continuing dialogue. Prior messages for {@code conversationId}
     * are replayed to the model, and the new user/assistant pair is appended on success.
     * Any config value left null falls back to the application.yml defaults.
     *
     * @throws AgentException if a policy rejects the exchange, the context window cannot
     *         hold the call, or the provider fails
     */
    public AgentResult handle(String conversationId, String userInput, AgentConfig config) {
        String id = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        AgentConfig effective = (config == null) ? defaults : config.withFallback(defaults);

        String prompt = inputPolicy.apply(userInput);

        // Before pricing, not after: the whole point is that this turn is the one that gets
        // cheaper, and the budget the caller is shown has to be the budget that was spent.
        int compacted = compressIfDue(id, effective);
        List<Message> history = conversations.history(id);
        Summary summary = conversations.summary(id);

        // Priced before a byte leaves the process: an oversized prompt is billed as a
        // rejection, so the cheapest place to find out it will not fit is here.
        ContextPlanner.ContextPlan plan = contextPlanner.plan(effective, summary, history, prompt);
        ContextBudget budget = plan.budget();
        if (budget.trimmed()) {
            log.info("Context trim: dropped {} oldest message(s) to fit {} of {} tokens",
                    budget.droppedMessages(), budget.projectedTokens(), budget.contextWindow());
        }

        ChatRequest request = buildRequest(plan.messages(), effective);

        long startedAt = System.nanoTime();
        ChatResponse response = llmClient.complete(request);
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        TokenUsage usage = response.usage();
        TokenUsage cumulative = usageTracker.record(usage);

        // The response is the only place the truth is ever stated. Feeding it back is what
        // keeps the next turn's pre-flight estimate honest.
        templateOverhead.observe(effective.model(), budget.countedTokens(), usage.promptTokens());

        log.info("Turn: prompt est {} / actual {} ({} history msgs, summary {} tok replacing {}), "
                        + "completion {}, window {}% used",
                budget.promptTokens(), usage.promptTokens(), history.size(),
                budget.summaryTokens(), budget.replacedTokens(),
                usage.completionTokens(), budget.usedPercent());

        String answer = outputPolicy.apply(response.content());
        Verdict verdict = judge.judge(prompt, answer, effective);

        // Committed only after the reply survives the output policy, so a failed turn
        // never poisons the history. Each message keeps the share of the turn it earned.
        conversations.append(id, List.of(
                Message.user(prompt).withStats(
                        MessageStats.forPrompt(usage.promptTokens(), effective.model())),
                Message.assistant(answer).withStats(
                        MessageStats.forCompletion(usage.completionTokens(), usage.totalTokens(),
                                latencyMillis, effective.model(), response.finishReason()))));

        return new AgentResult(answer, effective, usage, cumulative, budget,
                response.finishReason(), latencyMillis, verdict, conversations.history(id), compacted);
    }

    /** Read-only view of the message stack, for rendering an existing dialogue. */
    public List<Message> transcript(String conversationId) {
        return conversations.history(conversationId);
    }

    /** The notes standing in for whatever has already been compressed out of the transcript. */
    public Summary summary(String conversationId) {
        return conversations.summary(conversationId);
    }

    /**
     * What the dialogue already costs, before anything new is typed. Lets the window be
     * watched as it fills rather than only at the turn that breaks it.
     */
    public ContextBudget budget(String conversationId, AgentConfig config) {
        AgentConfig effective = (config == null) ? defaults : config.withFallback(defaults);
        return contextPlanner.budget(effective, conversations.summary(conversationId),
                conversations.history(conversationId));
    }

    /**
     * Folds the backlog into the summary when there is enough of it to be worth a call.
     * <p>
     * A summarization failure must not take the user's turn down with it. The worst case of
     * skipping it is a prompt that stays large for one more turn, which the overflow policy is
     * already there to catch; the worst case of propagating it is an agent that stops answering
     * because a background housekeeping call timed out.
     *
     * @return how many messages were folded, 0 if none
     */
    private int compressIfDue(String id, AgentConfig config) {
        if (!config.compressHistoryEnabled()) {
            return 0;
        }
        try {
            return compressor.compact(conversations.summary(id), conversations.history(id))
                    .map(compaction -> {
                        conversations.compact(id, compaction.summary(), compaction.foldedMessages());
                        log.info("Compressed {} message(s) worth {} tokens into summary rev {} — "
                                        + "every later turn now replays notes instead",
                                compaction.foldedMessages(), compaction.foldedTokens(),
                                compaction.summary().revision());
                        return compaction.foldedMessages();
                    })
                    .orElse(0);
        } catch (AgentException e) {
            log.warn("History compression failed ({}) — continuing with the full transcript.",
                    e.getMessage());
            return 0;
        }
    }

    /** Starts a new dialogue, discarding the message stack. */
    public void reset(String conversationId) {
        conversations.clear(conversationId);
    }

    private ChatRequest buildRequest(List<Message> messages, AgentConfig config) {
        return new ChatRequest(
                config.model(),
                messages,
                config.temperature(),
                config.maxCompletionTokens(),
                config.hasReasoningEffort() ? config.reasoningEffort() : null,
                config.stopSequencesOrEmpty(),
                config.hasResponseSchema() ? config.responseSchema() : null);
    }
}
