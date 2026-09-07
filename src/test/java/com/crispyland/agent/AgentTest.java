package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.PolicyViolationException;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent is testable with no Spring context and no network — that is the point of the boundary. */
class AgentTest {

    private final RecordingClient client = new RecordingClient();
    private final Agent agent = new Agent(client, new DefaultInputPolicy(100), new DefaultOutputPolicy(0),
            new NoOpJudge(), new TokenUsageTracker(), properties());

    @Test
    void unsetParametersFallBackToDefaults() {
        AgentResult result = agent.handle("  hi  ", AgentConfig.builder().temperature(0.2).build());

        assertThat(client.last.model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(client.last.temperature()).isEqualTo(0.2);
        assertThat(client.last.maxCompletionTokens()).isEqualTo(256);
        assertThat(client.last.messages()).containsExactly(
                new ChatRequest.Message("system", "be brief"),
                new ChatRequest.Message("user", "hi"));
        assertThat(result.answer()).isEqualTo("hello");
    }

    @Test
    void tokenUsageAccumulatesAcrossCalls() {
        agent.handle("one", null);
        AgentResult second = agent.handle("two", null);

        assertThat(second.usage().totalTokens()).isEqualTo(15);
        assertThat(second.cumulativeUsage().totalTokens()).isEqualTo(30);
    }

    @Test
    void blankInputNeverReachesTheModel() {
        assertThatThrownBy(() -> agent.handle("   ", null))
                .isInstanceOf(PolicyViolationException.class);
        assertThat(client.last).isNull();
    }

    private static AgentProperties properties() {
        return new AgentProperties("test-key", "https://example.invalid",
                Duration.ofSeconds(1), Duration.ofSeconds(1), List.of("openai/gpt-oss-20b"),
                new AgentProperties.Defaults("openai/gpt-oss-20b", "be brief", 1.0, 256,
                        "medium", List.of(), ""),
                new AgentProperties.Limit(100), new AgentProperties.Limit(0));
    }

    private static final class RecordingClient implements LlmClient {
        private ChatRequest last;

        @Override
        public ChatResponse complete(ChatRequest request) {
            this.last = request;
            return new ChatResponse("hello", request.model(), "stop", new TokenUsage(10, 5, 15));
        }
    }
}
