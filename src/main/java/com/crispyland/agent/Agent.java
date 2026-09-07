package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.Verdict;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The box. One entry point, one pipeline:
 * <pre>
 *   input policy -> merge config with defaults -> build request -> LlmClient
 *       -> record token usage -> output policy -> judge -> AgentResult
 * </pre>
 * Knows nothing about HTTP servlets, Thymeleaf, or forms; drop this package into an
 * empty Spring Boot app, add a @RestController that delegates to {@link #handle}, and
 * it is a microservice.
 */
@Service
public class Agent {

    private final LlmClient llmClient;
    private final InputPolicy inputPolicy;
    private final OutputPolicy outputPolicy;
    private final Judge judge;
    private final TokenUsageTracker usageTracker;
    private final AgentConfig defaults;

    public Agent(LlmClient llmClient,
                 InputPolicy inputPolicy,
                 OutputPolicy outputPolicy,
                 Judge judge,
                 TokenUsageTracker usageTracker,
                 AgentProperties properties) {
        this.llmClient = llmClient;
        this.inputPolicy = inputPolicy;
        this.outputPolicy = outputPolicy;
        this.judge = judge;
        this.usageTracker = usageTracker;
        this.defaults = properties.defaults().toConfig();
    }

    /**
     * The only public operation. Any config value left null falls back to the
     * application.yml defaults.
     *
     * @throws AgentException if a policy rejects the exchange or the provider fails
     */
    public AgentResult handle(String userInput, AgentConfig config) {
        AgentConfig effective = (config == null) ? defaults : config.withFallback(defaults);

        String prompt = inputPolicy.apply(userInput);
        ChatRequest request = buildRequest(prompt, effective);

        long startedAt = System.nanoTime();
        ChatResponse response = llmClient.complete(request);
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        TokenUsage usage = response.usage();
        TokenUsage cumulative = usageTracker.record(usage);

        String answer = outputPolicy.apply(response.content());
        Verdict verdict = judge.judge(prompt, answer, effective);

        return new AgentResult(answer, effective, usage, cumulative,
                response.finishReason(), latencyMillis, verdict);
    }

    private ChatRequest buildRequest(String prompt, AgentConfig config) {
        List<ChatRequest.Message> messages = new ArrayList<>(2);
        if (config.systemPrompt() != null && !config.systemPrompt().isBlank()) {
            messages.add(ChatRequest.Message.system(config.systemPrompt()));
        }
        messages.add(ChatRequest.Message.user(prompt));

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
