package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.Message;
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

    private final ScriptedClient client = new ScriptedClient();
    private final Agent agent = newAgent(20);

    private Agent newAgent(int maxMessages) {
        return new Agent(client, new DefaultInputPolicy(100), new DefaultOutputPolicy(0),
                new NoOpJudge(), new TokenUsageTracker(),
                new InMemoryConversationStore(maxMessages), properties());
    }

    @Test
    void unsetParametersFallBackToDefaults() {
        AgentResult result = agent.handle("c1", "  hi  ", AgentConfig.builder().temperature(0.2).build());

        assertThat(client.last.model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(client.last.temperature()).isEqualTo(0.2);
        assertThat(client.last.maxCompletionTokens()).isEqualTo(256);
        assertThat(result.answer()).isEqualTo("reply 1");
    }

    @Test
    void priorTurnsAreReplayedToTheModel() {
        agent.handle("c1", "my name is Nur", null);
        agent.handle("c1", "what is my name?", null);

        assertThat(client.last.messages()).containsExactly(
                Message.system("be brief"),
                Message.user("my name is Nur"),
                Message.assistant("reply 1"),
                Message.user("what is my name?"));
    }

    @Test
    void conversationsAreIsolatedFromEachOther() {
        agent.handle("alice", "hello from alice", null);
        agent.handle("bob", "hello from bob", null);

        assertThat(client.last.messages()).containsExactly(
                Message.system("be brief"),
                Message.user("hello from bob"));
        assertThat(agent.transcript("alice")).hasSize(2);
    }

    @Test
    void historyIsTrimmedToTheConfiguredWindow() {
        Agent windowed = newAgent(2);
        windowed.handle("c1", "first", null);
        windowed.handle("c1", "second", null);

        assertThat(windowed.transcript("c1")).containsExactly(
                Message.user("second"), Message.assistant("reply 2"));
    }

    @Test
    void systemPromptIsNotStoredSoItCanBeChangedMidConversation() {
        agent.handle("c1", "hello", null);
        agent.handle("c1", "again", AgentConfig.builder().systemPrompt("be a pirate").build());

        assertThat(client.last.messages().get(0)).isEqualTo(Message.system("be a pirate"));
        assertThat(agent.transcript("c1")).noneMatch(m -> Message.SYSTEM.equals(m.role()));
    }

    @Test
    void resetClearsTheDialogue() {
        agent.handle("c1", "hello", null);
        agent.reset("c1");

        assertThat(agent.transcript("c1")).isEmpty();
    }

    @Test
    void aFailedTurnDoesNotPoisonHistory() {
        agent.handle("c1", "good turn", null);
        client.failNext = true;

        assertThatThrownBy(() -> agent.handle("c1", "doomed turn", null))
                .isInstanceOf(LlmException.class);
        assertThat(agent.transcript("c1")).containsExactly(
                Message.user("good turn"), Message.assistant("reply 1"));
    }

    @Test
    void tokenUsageAccumulatesAcrossCalls() {
        agent.handle("c1", "one", null);
        AgentResult second = agent.handle("c1", "two", null);

        assertThat(second.usage().totalTokens()).isEqualTo(15);
        assertThat(second.cumulativeUsage().totalTokens()).isEqualTo(30);
    }

    @Test
    void blankInputNeverReachesTheModel() {
        assertThatThrownBy(() -> agent.handle("c1", "   ", null))
                .isInstanceOf(PolicyViolationException.class);
        assertThat(client.last).isNull();
    }

    private static AgentProperties properties() {
        return new AgentProperties("test-key", "https://example.invalid",
                Duration.ofSeconds(1), Duration.ofSeconds(1), List.of("openai/gpt-oss-20b"),
                List.of("", "low", "medium", "high"),
                new AgentProperties.Defaults("openai/gpt-oss-20b", "be brief", 1.0, 256,
                        "", List.of(), ""),
                new AgentProperties.Limit(100), new AgentProperties.Limit(0),
                new AgentProperties.Memory(20));
    }

    private static final class ScriptedClient implements LlmClient {
        private ChatRequest last;
        private boolean failNext;
        private int calls;

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (failNext) {
                failNext = false;
                throw new LlmException("simulated outage");
            }
            this.last = request;
            return new ChatResponse("reply " + (++calls), request.model(), "stop", new TokenUsage(10, 5, 15));
        }
    }
}
