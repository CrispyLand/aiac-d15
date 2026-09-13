package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.PolicyViolationException;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.OverflowPolicy;
import com.crispyland.agent.usage.TemplateOverhead;
import com.crispyland.agent.usage.TokenUsage;
import com.crispyland.agent.usage.TokenUsageTracker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The agent is testable with no Spring context and no network — that is the point of the boundary. */
class AgentTest {

    private final ScriptedClient client = new ScriptedClient();
    private final TemplateOverhead overhead = new TemplateOverhead();
    private final Agent agent = newAgent(20);

    /** Compression off by default, so the pre-existing turns are unaffected by it. */
    private Agent newAgent(int maxMessages) {
        return newAgent(maxMessages, planner(131_072, OverflowPolicy.FAIL), properties(false));
    }

    private Agent newAgent(int maxMessages, ContextPlanner planner) {
        return newAgent(maxMessages, planner, properties(false));
    }

    private Agent newAgent(int maxMessages, ContextPlanner planner, AgentProperties properties) {
        AgentProperties.Compression compression = properties.compression();
        return new Agent(client, new DefaultInputPolicy(100), new DefaultOutputPolicy(0),
                new NoOpJudge(), new TokenUsageTracker(),
                new InMemoryConversationStore(maxMessages), planner,
                new HistoryCompressor(client, new BpeTokenCounter(), "summarizer",
                        compression.keepRecentMessages(), compression.compressEvery(),
                        compression.maxSummaryTokens()),
                overhead, properties);
    }

    private ContextPlanner planner(int window, OverflowPolicy policy) {
        return new ContextPlanner(new BpeTokenCounter(), overhead, Map.of(), window, policy, 0.8);
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

    @Test
    void everyTurnReportsWhatItWasPredictedToCost() {
        AgentResult result = agent.handle("c1", "hello", null);

        // 256 reserved for the reply is the dominant term while the dialogue is still short —
        // the whole prompt is a rounding error next to the space held open for the answer.
        assertThat(result.budget().reservedCompletionTokens()).isEqualTo(256);
        assertThat(result.budget().promptTokens()).isPositive();
        assertThat(result.budget().projectedTokens())
                .isEqualTo(result.budget().promptTokens() + 256);
        assertThat(result.budget().overflowing()).isFalse();
    }

    @Test
    void historyIsThePartOfTheBudgetThatGrows() {
        long first = agent.handle("c1", "hello", null).budget().historyTokens();
        long second = agent.handle("c1", "hello again", null).budget().historyTokens();
        long third = agent.handle("c1", "and again", null).budget().historyTokens();

        System.out.println("  history tokens by turn: " + first + " -> " + second + " -> " + third);
        assertThat(first).isZero();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void anOversizedCallIsRefusedBeforeItIsPaidFor() {
        // 200-token window against 256 reserved for the reply: nothing can fit, ever.
        Agent tiny = newAgent(20, planner(200, OverflowPolicy.FAIL));

        assertThatThrownBy(() -> tiny.handle("c1", "hello", null))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("Context window exceeded");
        // The point of a local guard: no request, no bill, no round trip.
        assertThat(client.last).isNull();
    }

    @Test
    void withOverflowUnguardedTheRequestIsSentAnywayForTheProviderToReject() {
        Agent tiny = newAgent(20, planner(200, OverflowPolicy.OFF));

        AgentResult result = tiny.handle("c1", "hello", null);

        assertThat(client.last).isNotNull();
        assertThat(result.budget().overflowing()).isTrue();
        assertThat(result.budget().remainingTokens()).isNegative();
    }

    @Test
    void trimDropsOldestTurnsUntilTheCallFits() {
        // Window sized to hold the reserved reply, the system prompt and roughly one turn.
        Agent tiny = newAgent(20, planner(300, OverflowPolicy.TRIM));
        tiny.handle("c1", "the first thing I ever said in this conversation", null);
        tiny.handle("c1", "the second thing I ever said in this conversation", null);
        AgentResult third = tiny.handle("c1", "the third thing", null);

        assertThat(third.budget().trimmed()).isTrue();
        assertThat(third.budget().overflowing()).isFalse();
        // Forgotten, not merely unsent: the oldest turn is gone from what the model sees.
        assertThat(client.last.messages())
                .extracting(Message::content)
                .doesNotContain("the first thing I ever said in this conversation");
        // The window still opens on a user message, never mid-turn on an assistant reply.
        assertThat(client.last.messages().get(1).role()).isEqualTo("user");
    }

    @Test
    void trimStillFailsWhenTheNewMessageAloneCannotFit() {
        Agent tiny = newAgent(20, planner(260, OverflowPolicy.TRIM));

        assertThatThrownBy(() -> tiny.handle("c1", "hello", null))
                .isInstanceOf(ContextOverflowException.class);
    }

    @Test
    void theEstimateIsHeldAgainstTheProvidersBillAndCorrectedByIt() {
        AgentResult first = agent.handle("c1", "hello", null);

        assertThat(first.promptTokenDrift())
                .isEqualTo(first.budget().promptTokens() - first.usage().promptTokens());
        // Nothing was known about the model's chat template before the first response.
        assertThat(first.budget().calibrated()).isFalse();

        // From the second turn on, the gap between the local count and the provider's bill
        // has been observed and is folded into the estimate.
        assertThat(agent.handle("c1", "hello again", null).budget().calibrated()).isTrue();
    }

    @Test
    void aReplyCutOffAtTheTokenLimitIsFlagged() {
        client.finishReason = "length";

        assertThat(agent.handle("c1", "hello", null).truncated()).isTrue();
    }

    // --- history compression -------------------------------------------------------------

    @Test
    void theOldestTurnsAreReplacedByASummaryRatherThanReplayed() {
        Agent compressing = compressingAgent();
        AgentResult fourth = fourTurns(compressing);

        // Folded before the fourth request was built, so that request is already the cheap one.
        assertThat(fourth.compactedMessages()).isEqualTo(4);
        assertThat(client.last.messages())
                .extracting(Message::role)
                .containsExactly("system", "system", "user", "assistant", "user");
        // The first two turns are gone from the wire...
        assertThat(client.last.messages()).extracting(Message::content)
                .noneMatch(content -> content.contains("my name is Nur"));
        // ...but they were handed to the summarizer before being dropped, and what it wrote
        // is what the model now reads in their place.
        assertThat(client.brief()).contains("my name is Nur").contains("I work on meetupper");
        assertThat(client.last.messages().get(1).content()).contains("notes rev 1");
    }

    @Test
    void theSummaryReplacesThoseMessagesInStorageToo() {
        Agent compressing = compressingAgent();
        fourTurns(compressing);

        // Two verbatim messages survived the fold, plus the turn that has just been appended.
        assertThat(compressing.transcript("c1")).hasSize(4);
        assertThat(compressing.summary("c1").coveredMessages()).isEqualTo(4);
        assertThat(compressing.summary("c1").revision()).isEqualTo(1);
        assertThat(compressing.summary("c1").buildTokens()).isEqualTo(60);
    }

    @Test
    void onFourWordTurnsTheSummaryCostsAboutWhatItReplaced() {
        ContextBudget budget = fourTurns(compressingAgent()).budget();

        System.out.println("  toy turns: " + budget.replacedTokens() + " tokens replaced by a "
                + budget.summaryTokens() + "-token summary -> saved " + budget.savedTokens());
        // Worth stating rather than hiding: compression is a trade. The saving comes from the
        // length of what is folded, and a dialogue of four-word turns has nothing to give.
        assertThat(budget.compressed()).isTrue();
        assertThat(budget.savedTokens()).isLessThan(20);
    }

    @Test
    void onRealisticTurnsTheCompressedPromptIsTheSmallerOne() {
        long uncompressed = wordyDialogue(newAgent(20)).budget().promptTokens();
        ContextBudget compressed = wordyDialogue(compressingAgent()).budget();

        System.out.println("  prompt tokens on turn 4: " + uncompressed + " uncompressed -> "
                + compressed.promptTokens() + " compressed ("
                + compressed.savedPercent() + "% saved)");
        assertThat(compressed.promptTokens()).isLessThan(uncompressed);
        assertThat(compressed.savedTokens())
                .isEqualTo(compressed.replacedTokens() - compressed.summaryTokens())
                .isPositive();
        // The claimed baseline is not a guess: it reconstructs the very prompt the other agent
        // just sent, token for token.
        assertThat(compressed.uncompressedPromptTokens()).isEqualTo(uncompressed);
    }

    @Test
    void switchingCompressionOffForOneTurnReplaysEverythingAgain() {
        Agent compressing = compressingAgent();
        threeTurns(compressing);
        compressing.handle("c1", "what is my name?",
                AgentConfig.builder().compressHistory(false).build());

        assertThat(client.summarizations).isZero();
        assertThat(client.last.messages()).extracting(Message::content).contains("my name is Nur");
    }

    @Test
    void aSummarizerOutageCostsNothingButOneUncompressedTurn() {
        Agent compressing = compressingAgent();
        client.failSummarization = true;
        AgentResult fourth = fourTurns(compressing);

        // The turn still answered, and nothing was dropped on the strength of a summary that
        // was never written.
        assertThat(fourth.answer()).isNotBlank();
        assertThat(fourth.compactedMessages()).isZero();
        assertThat(compressing.summary("c1").isPresent()).isFalse();
        assertThat(client.last.messages()).extracting(Message::content).contains("my name is Nur");
    }

    @Test
    void resettingForgetsTheSummaryAndNotJustTheMessages() {
        Agent compressing = compressingAgent();
        fourTurns(compressing);
        compressing.reset("c1");

        assertThat(compressing.summary("c1").isPresent()).isFalse();
        assertThat(compressing.transcript("c1")).isEmpty();
    }

    private Agent compressingAgent() {
        return newAgent(20, planner(131_072, OverflowPolicy.FAIL), properties(true));
    }

    /** Enough turns to put four messages behind the two-message verbatim tail. */
    private AgentResult fourTurns(Agent target) {
        threeTurns(target);
        return target.handle("c1", "what is my name?", null);
    }

    private void threeTurns(Agent target) {
        target.handle("c1", "my name is Nur", null);
        target.handle("c1", "I work on meetupper", null);
        target.handle("c1", "it is a Spring app", null);
    }

    /** The same four turns, at the length a real message actually runs to. */
    private AgentResult wordyDialogue(Agent target) {
        target.handle("c1", "my name is Nur and I am building an agent in Spring Boot "
                + "that talks to Groq models", null);
        target.handle("c1", "it keeps a transcript and replays every word of it on each "
                + "turn, which gets expensive", null);
        target.handle("c1", "today I am adding compression so the old turns become notes "
                + "instead of whole messages", null);
        return target.handle("c1", "what is my name?", null);
    }

    /** Keep the last 2 messages verbatim and fold once 4 more have piled up behind them. */
    private static AgentProperties properties(boolean compress) {
        return new AgentProperties("test-key", "https://example.invalid",
                Duration.ofSeconds(1), Duration.ofSeconds(1), List.of("openai/gpt-oss-20b"),
                List.of("", "low", "medium", "high"),
                new AgentProperties.Defaults("openai/gpt-oss-20b", "be brief", 1.0, 256,
                        "", List.of(), ""),
                new AgentProperties.Limit(100), new AgentProperties.Limit(0),
                new AgentProperties.Memory(20, "memory", ""),
                new AgentProperties.Context(Map.of(), 131_072, OverflowPolicy.FAIL, 0.8),
                new AgentProperties.Compression(compress, 2, 4, "summarizer", 120, "low"));
    }

    /**
     * Summarization goes through the same client as a user turn, so the double is told apart by
     * model id — which is also the cheapest proof that compression really is an extra billed call.
     */
    private static final class ScriptedClient implements LlmClient {
        private static final String SUMMARIZER = "summarizer";

        private ChatRequest last;
        private ChatRequest lastSummarization;
        private int summarizations;
        private boolean failNext;
        private boolean failSummarization;
        private String finishReason = "stop";
        private int calls;

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (SUMMARIZER.equals(request.model())) {
                lastSummarization = request;
                summarizations++;
                if (failSummarization) {
                    throw new LlmException("summarizer unavailable");
                }
                return new ChatResponse("notes rev " + summarizations, request.model(), "stop",
                        new TokenUsage(40, 20, 60));
            }
            if (failNext) {
                failNext = false;
                throw new LlmException("simulated outage");
            }
            this.last = request;
            return new ChatResponse("reply " + (++calls), request.model(), finishReason,
                    new TokenUsage(10, 5, 15));
        }

        /** What the summarizer was actually shown — the messages about to be thrown away. */
        private String brief() {
            return lastSummarization.messages().get(1).content();
        }
    }
}
