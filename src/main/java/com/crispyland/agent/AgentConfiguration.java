package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.GroqLlmClient;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.JsonFileConversationStore;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenCounter;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the agent's collaborators. Every one is {@code @ConditionalOnMissingBean}, so any
 * piece — client, policies, judge — can be replaced by declaring your own bean.
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public LlmClient llmClient(AgentProperties properties) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        ObjectMapper mapper = JsonMapper.builder().build();
        return new GroqLlmClient(restClient, mapper, properties.endpoint(), properties.apiKey());
    }

    @Bean
    @ConditionalOnMissingBean
    public InputPolicy inputPolicy(AgentProperties properties) {
        return new DefaultInputPolicy(properties.inputPolicy().maxLength());
    }

    @Bean
    @ConditionalOnMissingBean
    public OutputPolicy outputPolicy(AgentProperties properties) {
        return new DefaultOutputPolicy(properties.outputPolicy().maxLength());
    }

    @Bean
    @ConditionalOnMissingBean
    public Judge judge() {
        return new NoOpJudge();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenUsageTracker tokenUsageTracker() {
        return new TokenUsageTracker();
    }

    /** Loaded once — the BPE vocabulary is a few megabytes and is immutable and thread-safe. */
    @Bean
    @ConditionalOnMissingBean
    public TokenCounter tokenCounter() {
        return new BpeTokenCounter();
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplateOverhead templateOverhead() {
        return new TemplateOverhead();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextPlanner contextPlanner(TokenCounter tokenCounter, TemplateOverhead templateOverhead,
                                         AgentProperties properties) {
        AgentProperties.Context context = properties.context();
        return new ContextPlanner(tokenCounter, templateOverhead, context.windows(),
                context.defaultWindow(), context.overflowPolicy(), context.warnAt());
    }

    /**
     * {@code json} keeps the dialogue across restarts; anything else forgets it on shutdown.
     */
    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(AgentProperties properties) {
        AgentProperties.Memory memory = properties.memory();
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(memory.store())) {
            return new JsonFileConversationStore(JsonMapper.builder().build(),
                    Path.of(memory.file()), memory.maxMessages());
        }
        return new InMemoryConversationStore(memory.maxMessages());
    }
}
