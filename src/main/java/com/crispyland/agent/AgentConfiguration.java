package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.GroqLlmClient;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.InputPolicy;
import com.crispyland.agent.policy.OutputPolicy;
import com.crispyland.agent.usage.TokenUsageTracker;
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

    @Bean
    @ConditionalOnMissingBean
    public ConversationStore conversationStore(AgentProperties properties) {
        return new InMemoryConversationStore(properties.memory().maxMessages());
    }
}
