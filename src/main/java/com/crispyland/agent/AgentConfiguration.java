package com.crispyland.agent;

import com.crispyland.agent.judge.Judge;
import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.GroqLlmClient;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.memory.BranchStore;
import com.crispyland.agent.memory.ConversationStore;
import com.crispyland.agent.memory.FactExtractor;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.InMemoryBranchStore;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.JsonFileBranchStore;
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
                context.defaultWindow(), context.overflowPolicy(), context.warnAt(),
                properties.windowMessages());
    }

    /**
     * Summarization is an ordinary call through the same client, so it is billed, logged and
     * rate-limited exactly like a user turn — because it is one.
     */
    @Bean
    @ConditionalOnMissingBean
    public HistoryCompressor historyCompressor(LlmClient llmClient, TokenCounter tokenCounter,
                                               AgentProperties properties) {
        AgentProperties.Compression compression = properties.compression();
        String model = (compression.model() == null || compression.model().isBlank())
                ? properties.defaults().model()
                : compression.model();
        return new HistoryCompressor(llmClient, tokenCounter, model, compression.keepRecentMessages(),
                compression.compressEvery(), compression.maxSummaryTokens(),
                compression.reasoningEffort());
    }

    /**
     * Also an ordinary call through the same client — and unlike the summarizer it runs on
     * every single turn, which is the cost that makes this strategy different rather than the
     * size of the block it produces.
     */
    @Bean
    @ConditionalOnMissingBean
    public FactExtractor factExtractor(LlmClient llmClient, AgentProperties properties) {
        AgentProperties.FactMemory facts = properties.facts();
        String model = (facts.model() == null || facts.model().isBlank())
                ? properties.defaults().model()
                : facts.model();
        return new FactExtractor(llmClient, model, facts.maxFacts(), facts.maxTokens(),
                facts.reasoningEffort());
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

    /** Refs follow the transcripts: persisted together, forgotten together. */
    @Bean
    @ConditionalOnMissingBean
    public BranchStore branchStore(AgentProperties properties) {
        AgentProperties.Memory memory = properties.memory();
        if (AgentProperties.Memory.JSON.equalsIgnoreCase(memory.store())) {
            return new JsonFileBranchStore(JsonMapper.builder().build(), Path.of(memory.branchFile()));
        }
        return new InMemoryBranchStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public Branches branches(BranchStore branchStore, ConversationStore conversationStore) {
        return new Branches(branchStore, conversationStore);
    }
}
