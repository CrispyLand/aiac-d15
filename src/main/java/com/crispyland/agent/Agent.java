package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.MessageStats;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The box. One pipeline:
 * <pre>
 *   input policy -> merge config with defaults -> load history -> build request
 *       -> LlmClient -> record token usage -> output policy -> judge
 *       -> append turn to history -> AgentResult
 * </pre>
 * The conversation is the agent's own state, not the web layer's: callers pass a
 * conversation id and the agent decides what history to send, how to window it, and when
 * to commit a turn. Knows nothing about HTTP servlets, Thymeleaf, or forms.
 */
@Service
public class Agent {

    private final LlmClient llmClient;
    private final InputPolicy inputPolicy;
    private final OutputPolicy outputPolicy;
    private final Judge judge;
    private final TokenUsageTracker usageTracker;
    private final ConversationStore conversations;
    private final AgentConfig defaults;

    public Agent(LlmClient llmClient,
                 InputPolicy inputPolicy,
                 OutputPolicy outputPolicy,
                 Judge judge,
                 TokenUsageTracker usageTracker,
                 ConversationStore conversations,
                 AgentProperties properties) {
        this.llmClient = llmClient;
        this.inputPolicy = inputPolicy;
        this.outputPolicy = outputPolicy;
        this.judge = judge;
        this.usageTracker = usageTracker;
        this.conversations = conversations;
        this.defaults = properties.defaults().toConfig();
    }

    /**
     * Handles one turn of a continuing dialogue. Prior messages for {@code conversationId}
     * are replayed to the model, and the new user/assistant pair is appended on success.
     * Any config value left null falls back to the application.yml defaults.
     *
     * @throws AgentException if a policy rejects the exchange or the provider fails
     */
    public AgentResult handle(String conversationId, String userInput, AgentConfig config) {
        String id = (conversationId == null || conversationId.isBlank()) ? "default" : conversationId;
        AgentConfig effective = (config == null) ? defaults : config.withFallback(defaults);

        String prompt = inputPolicy.apply(userInput);
        List<Message> history = conversations.history(id);
        ChatRequest request = buildRequest(prompt, history, effective);

        long startedAt = System.nanoTime();
        ChatResponse response = llmClient.complete(request);
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        TokenUsage usage = response.usage();
        TokenUsage cumulative = usageTracker.record(usage);

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

        return new AgentResult(answer, effective, usage, cumulative,
                response.finishReason(), latencyMillis, verdict, conversations.history(id));
    }

    /** Read-only view of the message stack, for rendering an existing dialogue. */
    public List<Message> transcript(String conversationId) {
        return conversations.history(conversationId);
    }

    /** Starts a new dialogue, discarding the message stack. */
    public void reset(String conversationId) {
        conversations.clear(conversationId);
    }

    private ChatRequest buildRequest(String prompt, List<Message> history, AgentConfig config) {
        List<Message> messages = new ArrayList<>(history.size() + 2);
        // The system prompt is re-applied fresh each turn rather than stored, so editing it
        // on the page takes effect immediately for the rest of the conversation.
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            messages.add(Message.system(config.systemPrompt()));
        }
        messages.addAll(history);
        messages.add(Message.user(prompt));

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
