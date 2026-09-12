package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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

        assertThat(client.last.messages())
                .extracting(Message::role, Message::content)
                .containsExactly(
                        tuple("system", "be brief"),
                        tuple("user", "my name is Nur"),
                        tuple("assistant", "reply 1"),
                        tuple("user", "what is my name?"));
    }

    @Test
    void conversationsAreIsolatedFromEachOther() {
        agent.handle("alice", "hello from alice", null);
        agent.handle("bob", "hello from bob", null);

        assertThat(client.last.messages())
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("system", "be brief"), tuple("user", "hello from bob"));
        assertThat(agent.transcript("alice")).hasSize(2);
    }

    @Test
    void historyIsTrimmedToTheConfiguredWindow() {
        Agent windowed = newAgent(2);
        windowed.handle("c1", "first", null);
        windowed.handle("c1", "second", null);

        assertThat(windowed.transcript("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "second"), tuple("assistant", "reply 2"));
    }

    @Test
    void eachStoredMessageCarriesItsShareOfTheTurn() {
        agent.handle("c1", "hello", null);
        List<Message> transcript = agent.transcript("c1");

        // The user message owns the prompt tokens, the assistant message the completion.
        assertThat(transcript.get(0).stats().promptTokens()).isEqualTo(10);
        assertThat(transcript.get(0).stats().completionTokens()).isZero();
        assertThat(transcript.get(1).stats().completionTokens()).isEqualTo(5);
        assertThat(transcript.get(1).stats().totalTokens()).isEqualTo(15);
        assertThat(transcript.get(1).stats().model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(transcript.get(1).stats().finishReason()).isEqualTo("stop");
    }

    @Test
    void systemPromptIsNotStoredSoItCanBeChangedMidConversation() {
        agent.handle("c1", "hello", null);
        agent.handle("c1", "again", AgentConfig.builder().systemPrompt("be a pirate").build());

        assertThat(client.last.messages().get(0)).isEqualTo(Message.system("be a pirate"));
        assertThat(agent.transcript("c1")).noneMatch(m -> Message.SYSTEM.equals(m.role()));
    }

    @Test
    void systemMessageIsRebuiltOnceAtPositionZeroEveryTurn() {
        agent.handle("c1", "turn one", null);
        printRoles(1);
        agent.handle("c1", "turn two", null);
        printRoles(2);
        // System prompt edited on the page mid-conversation.
        agent.handle("c1", "turn three", AgentConfig.builder().systemPrompt("be a pirate").build());
        printRoles(3);

        List<Message> sent = client.last.messages();
        assertThat(sent).extracting(Message::role)
                .containsExactly("system", "user", "assistant", "user", "assistant", "user");
        assertThat(sent).filteredOn(m -> Message.SYSTEM.equals(m.role())).hasSize(1);
        // Rewritten in place with the new value, not appended as a second system message.
        assertThat(sent.get(0).content()).isEqualTo("be a pirate");
        assertThat(agent.transcript("c1")).noneMatch(m -> Message.SYSTEM.equals(m.role()));
    }

    private void printRoles(int turn) {
        System.out.println("  turn " + turn + " outgoing roles: "
                + client.last.messages().stream().map(Message::role).toList()
                + "  (system content: \"" + client.last.messages().get(0).content() + "\")");
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
        assertThat(agent.transcript("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "good turn"), tuple("assistant", "reply 1"));
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
                new AgentProperties.Memory(20, "memory", ""));
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
