package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.crispyland.agent.judge.NoOpJudge;
import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.memory.Facts;
import com.crispyland.agent.memory.HistoryCompressor;
import com.crispyland.agent.memory.InMemoryConversationStore;
import com.crispyland.agent.memory.InMemoryLongTermStore;
import com.crispyland.agent.memory.LongTermKind;
import com.crispyland.agent.memory.LongTermMemory;
import com.crispyland.agent.memory.LongTermStore;
import com.crispyland.agent.memory.MemoryExtractor;
import com.crispyland.agent.memory.MemoryScope;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.profile.Lens;
import com.crispyland.agent.profile.Limits;
import com.crispyland.agent.profile.Persona;
import com.crispyland.agent.profile.UserProfile;
import com.crispyland.agent.policy.DefaultInputPolicy;
import com.crispyland.agent.policy.DefaultOutputPolicy;
import com.crispyland.agent.policy.PolicyViolationException;
import com.crispyland.agent.task.PausedTurn;
import com.crispyland.agent.task.TaskStage;
import com.crispyland.agent.task.TaskState;
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

    /** One visitor on their trunk branch, where the branch key is the visitor id. */
    private static final MemoryScope C1 = MemoryScope.of("c1");

    /** A profile that sets both limits, so "declared beats default" is visible on the wire. */
    private static final UserProfile RUSSELL = new UserProfile("russell", "Russell", "Russian",
            "formal", new UserProfile.Format("short bullet points", 120), List.of("use emoji"),
            new Limits(400, 0.2, null), null);

    private final ScriptedClient client = new ScriptedClient();
    private final TemplateOverhead overhead = new TemplateOverhead();
    private final LongTermStore longTerm = new InMemoryLongTermStore(24);
    private final Agent agent = newAgent(20);

    /**
     * A backlog threshold no test reaches, so the transcript is replayed whole. Compression is
     * not switchable any more — the only honest way to hold it off is to say it is not yet worth
     * a call, which is exactly what {@code compress-every} means.
     */
    private static final int NEVER_FOLDS = 999;

    private Agent newAgent(int maxMessages) {
        return newAgent(maxMessages, planner(131_072, OverflowPolicy.FAIL), properties(NEVER_FOLDS));
    }

    private Agent newAgent(int maxMessages, ContextPlanner planner) {
        return newAgent(maxMessages, planner, properties(NEVER_FOLDS));
    }

    private Agent newAgent(int maxMessages, ContextPlanner planner, AgentProperties properties) {
        AgentProperties.Compression compression = properties.compression();
        AgentProperties.FactMemory facts = properties.facts();
        return new Agent(client, new DefaultInputPolicy(100), new DefaultOutputPolicy(0),
                new NoOpJudge(), new TokenUsageTracker(),
                new InMemoryConversationStore(maxMessages), longTerm, planner,
                new HistoryCompressor(client, new BpeTokenCounter(), "summarizer",
                        compression.keepRecentMessages(), compression.compressEvery(),
                        compression.maxSummaryTokens()),
                new MemoryExtractor(client, facts.model(), facts.maxFacts(), facts.maxTokens(),
                        facts.reasoningEffort()),
                overhead, properties);
    }

    private ContextPlanner planner(int window, OverflowPolicy policy) {
        return new ContextPlanner(new BpeTokenCounter(), overhead, Map.of(), window, policy, 0.8);
    }

    @Test
    void unsetParametersFallBackToDefaults() {
        AgentResult result = agent.handle(C1, Persona.NONE, "  hi  ", AgentConfig.builder().temperature(0.2).build());

        assertThat(client.last.model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(client.last.temperature()).isEqualTo(0.2);
        assertThat(client.last.maxCompletionTokens()).isEqualTo(256);
        assertThat(result.answer()).isEqualTo("reply 1");
    }

    @Test
    void priorTurnsAreReplayedToTheModel() {
        agent.handle(C1, Persona.NONE, "my name is Nur", null);
        agent.handle(C1, Persona.NONE, "what is my name?", null);

        // Two system messages, not one: the prompt is the instruction, the block below it is
        // working memory. Gluing them together is what makes a model read state as a directive.
        assertThat(client.last.messages())
                .extracting(Message::role, Message::content)
                .containsSequence(
                        tuple("user", "my name is Nur"),
                        tuple("assistant", "reply 1"),
                        tuple("user", "what is my name?"));
        assertThat(client.last.messages().get(0)).isEqualTo(Message.system("be brief"));
    }

    @Test
    void conversationsAreIsolatedFromEachOther() {
        agent.handle(MemoryScope.of("alice"), Persona.NONE, "hello from alice", null);
        agent.handle(MemoryScope.of("bob"), Persona.NONE, "hello from bob", null);

        // Working memory is per conversation too — bob's block is his own, not a view of alice's.
        assertThat(client.last.messages()).extracting(Message::content)
                .doesNotContain("hello from alice")
                .contains("hello from bob");
        assertThat(agent.transcript("alice")).hasSize(2);
        assertThat(agent.facts("bob").entries()).isNotEqualTo(agent.facts("alice").entries());
    }

    @Test
    void historyIsTrimmedToTheConfiguredWindow() {
        Agent windowed = newAgent(2);
        windowed.handle(C1, Persona.NONE, "first", null);
        windowed.handle(C1, Persona.NONE, "second", null);

        assertThat(windowed.transcript("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "second"), tuple("assistant", "reply 2"));
    }

    @Test
    void eachStoredMessageCarriesItsShareOfTheTurn() {
        agent.handle(C1, Persona.NONE, "hello", null);
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
        agent.handle(C1, Persona.NONE, "hello", null);
        agent.handle(C1, Persona.NONE, "again", AgentConfig.builder().systemPrompt("be a pirate").build());

        assertThat(client.last.messages().get(0)).isEqualTo(Message.system("be a pirate"));
        assertThat(agent.transcript("c1")).noneMatch(m -> Message.SYSTEM.equals(m.role()));
    }

    @Test
    void systemMessageIsRebuiltOnceAtPositionZeroEveryTurn() {
        agent.handle(C1, Persona.NONE, "turn one", null);
        printRoles(1);
        agent.handle(C1, Persona.NONE, "turn two", null);
        printRoles(2);
        // System prompt edited on the page mid-conversation.
        agent.handle(C1, Persona.NONE, "turn three", AgentConfig.builder().systemPrompt("be a pirate").build());
        printRoles(3);

        List<Message> sent = client.last.messages();
        assertThat(sent).extracting(Message::role)
                .containsSequence("user", "assistant", "user", "assistant", "user");
        // Rewritten in place with the new value, not appended as a second copy of itself. The
        // other system message is the working-memory block, which is state rather than instruction.
        assertThat(sent.get(0).content()).isEqualTo("be a pirate");
        assertThat(sent).filteredOn(m -> "be brief".equals(m.content())).isEmpty();
        assertThat(agent.transcript("c1")).noneMatch(m -> Message.SYSTEM.equals(m.role()));
    }

    private void printRoles(int turn) {
        System.out.println("  turn " + turn + " outgoing roles: "
                + client.last.messages().stream().map(Message::role).toList()
                + "  (system content: \"" + client.last.messages().get(0).content() + "\")");
    }

    @Test
    void resetClearsTheDialogue() {
        agent.handle(C1, Persona.NONE, "hello", null);
        agent.reset("c1");

        assertThat(agent.transcript("c1")).isEmpty();
    }

    @Test
    void aFailedTurnDoesNotPoisonHistory() {
        agent.handle(C1, Persona.NONE, "good turn", null);
        client.failNext = true;

        assertThatThrownBy(() -> agent.handle(C1, Persona.NONE, "doomed turn", null))
                .isInstanceOf(LlmException.class);
        assertThat(agent.transcript("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "good turn"), tuple("assistant", "reply 1"));
    }

    @Test
    void tokenUsageAccumulatesAcrossCalls() {
        agent.handle(C1, Persona.NONE, "one", null);
        AgentResult second = agent.handle(C1, Persona.NONE, "two", null);

        assertThat(second.usage().totalTokens()).isEqualTo(15);
        assertThat(second.cumulativeUsage().totalTokens()).isEqualTo(30);
    }

    @Test
    void blankInputNeverReachesTheModel() {
        assertThatThrownBy(() -> agent.handle(C1, Persona.NONE, "   ", null))
                .isInstanceOf(PolicyViolationException.class);
        assertThat(client.last).isNull();
    }

    @Test
    void everyTurnReportsWhatItWasPredictedToCost() {
        AgentResult result = agent.handle(C1, Persona.NONE, "hello", null);

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
        long first = agent.handle(C1, Persona.NONE, "hello", null).budget().historyTokens();
        long second = agent.handle(C1, Persona.NONE, "hello again", null).budget().historyTokens();
        long third = agent.handle(C1, Persona.NONE, "and again", null).budget().historyTokens();

        System.out.println("  history tokens by turn: " + first + " -> " + second + " -> " + third);
        assertThat(first).isZero();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void anOversizedCallIsRefusedBeforeItIsPaidFor() {
        // 200-token window against 256 reserved for the reply: nothing can fit, ever.
        Agent tiny = newAgent(20, planner(200, OverflowPolicy.FAIL));

        assertThatThrownBy(() -> tiny.handle(C1, Persona.NONE, "hello", null))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("Context window exceeded");
        // The point of a local guard: no request, no bill, no round trip.
        assertThat(client.last).isNull();
    }

    @Test
    void withOverflowUnguardedTheRequestIsSentAnywayForTheProviderToReject() {
        Agent tiny = newAgent(20, planner(200, OverflowPolicy.OFF));

        AgentResult result = tiny.handle(C1, Persona.NONE, "hello", null);

        assertThat(client.last).isNotNull();
        assertThat(result.budget().overflowing()).isTrue();
        assertThat(result.budget().remainingTokens()).isNegative();
    }

    @Test
    void trimDropsOldestTurnsUntilTheCallFits() {
        // Window sized to hold the reserved reply, the system prompt, the working-memory block
        // and roughly one turn. Trim can only reach the transcript — the layers above it are
        // fixed costs, which is exactly why they have to be priced separately from history.
        Agent tiny = newAgent(20, planner(340, OverflowPolicy.TRIM));
        tiny.handle(C1, Persona.NONE, "the first thing I ever said in this conversation", null);
        tiny.handle(C1, Persona.NONE, "the second thing I ever said in this conversation", null);
        AgentResult third = tiny.handle(C1, Persona.NONE, "the third thing", null);

        assertThat(third.budget().trimmed()).isTrue();
        assertThat(third.budget().overflowing()).isFalse();
        // Forgotten, not merely unsent: the oldest turn is gone from what the model sees.
        assertThat(client.last.messages())
                .extracting(Message::content)
                .doesNotContain("the first thing I ever said in this conversation");
        // The transcript still opens on a user message, never mid-turn on an assistant reply.
        assertThat(client.last.messages().stream()
                .filter(m -> !Message.SYSTEM.equals(m.role())).findFirst().orElseThrow().role())
                .isEqualTo("user");
    }

    @Test
    void trimStillFailsWhenTheNewMessageAloneCannotFit() {
        Agent tiny = newAgent(20, planner(260, OverflowPolicy.TRIM));

        assertThatThrownBy(() -> tiny.handle(C1, Persona.NONE, "hello", null))
                .isInstanceOf(ContextOverflowException.class);
    }

    @Test
    void theEstimateIsHeldAgainstTheProvidersBillAndCorrectedByIt() {
        AgentResult first = agent.handle(C1, Persona.NONE, "hello", null);

        assertThat(first.promptTokenDrift())
                .isEqualTo(first.budget().promptTokens() - first.usage().promptTokens());
        // Nothing was known about the model's chat template before the first response.
        assertThat(first.budget().calibrated()).isFalse();

        // From the second turn on, the gap between the local count and the provider's bill
        // has been observed and is folded into the estimate.
        assertThat(agent.handle(C1, Persona.NONE, "hello again", null).budget().calibrated()).isTrue();
    }

    @Test
    void aReplyCutOffAtTheTokenLimitIsFlagged() {
        client.finishReason = "length";

        assertThat(agent.handle(C1, Persona.NONE, "hello", null).truncated()).isTrue();
    }

    // --- history compression -------------------------------------------------------------

    @Test
    void theOldestTurnsAreReplacedByASummaryRatherThanReplayed() {
        Agent compressing = compressingAgent();
        AgentResult fourth = fourTurns(compressing);

        // Folded before the fourth request was built, so that request is already the cheap one.
        assertThat(fourth.compactedMessages()).isEqualTo(4);
        // Instruction, working memory, the notes, then the tail still held verbatim.
        assertThat(client.last.messages())
                .extracting(Message::role)
                .containsExactly("system", "system", "system", "user", "assistant", "user");
        // The first two turns are gone from the wire...
        assertThat(client.last.messages()).extracting(Message::content)
                .noneMatch(content -> content.contains("my name is Nur"));
        // ...but they were handed to the summarizer before being dropped, and what it wrote
        // is what the model now reads in their place.
        assertThat(client.brief()).contains("my name is Nur").contains("I work on meetupper");
        assertThat(client.last.messages().get(2).content()).contains("notes rev 1");
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

    // --- working memory ------------------------------------------------------------------

    @Test
    void workingMemoryIsMaintainedOnEveryTurnAndNotJustEveryNth() {
        // The two short-term mechanisms have very different cost curves: the summarizer fires
        // once every few turns, the extractor fires on all of them.
        threeTurns(agent);

        assertThat(client.extractions).isEqualTo(3);
        assertThat(client.summarizations).isZero();
    }

    @Test
    void theWorkingBlockIsSentAsSystemContextRatherThanAsSomethingSomebodySaid() {
        threeTurns(agent);

        assertThat(agent.facts("c1").isPresent()).isTrue();
        Message block = client.last.messages().stream()
                .filter(m -> "system".equals(m.role()) && m.content().contains("fact3: value 3"))
                .findFirst().orElseThrow();
        assertThat(block.content()).contains("current and authoritative");
    }

    @Test
    void anExtractorOutageCostsOneStaleTurnButNotTheAnswer() {
        client.failExtraction = true;
        AgentResult result = agent.handle(C1, Persona.NONE, "my name is Nur", null);

        assertThat(result.answer()).isNotBlank();
        assertThat(agent.facts("c1").isPresent()).isFalse();
    }

    @Test
    void resettingForgetsWorkingMemoryAndNotJustTheMessages() {
        threeTurns(agent);
        agent.reset("c1");

        assertThat(agent.facts("c1").isPresent()).isFalse();
        assertThat(agent.transcript("c1")).isEmpty();
    }

    // --- long-term memory ----------------------------------------------------------------

    @Test
    void longTermMemoryIsSentAheadOfTheConversationAndLabelledAsOverridable() {
        longTerm.remember("c1", List.of(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));

        agent.handle(C1, Persona.NONE, "hello", null);

        Message block = client.last.messages().stream()
                .filter(m -> "system".equals(m.role()) && m.content().contains("name: Nur"))
                .findFirst().orElseThrow();
        // Grouped under its heading, so "Decisions" and "what you told me" are not read as
        // carrying the same authority.
        assertThat(block.content()).contains("Profile (Who you are):");
        // Everything below this block is newer than it, so the newer layer has to be allowed
        // to win — otherwise a stale profile argues with a correction made a minute ago.
        assertThat(block.content()).contains("let anything in this conversation override it");
    }

    @Test
    void resettingTheConversationDoesNotForgetTheVisitor() {
        longTerm.remember("c1", List.of(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));
        threeTurns(agent);
        agent.reset("c1");

        // The observable difference between the layers, and the whole reason they are separate:
        // short-term and working are gone, long-term is untouched.
        assertThat(agent.transcript("c1")).isEmpty();
        assertThat(agent.facts("c1").isPresent()).isFalse();
        assertThat(agent.recall("c1").of(LongTermKind.PROFILE))
                .extracting(LongTermMemory.Entry::value).containsExactly("Nur");
    }

    @Test
    void longTermMemoryIsKeyedByVisitorSoItFollowsThemAcrossBranches() {
        longTerm.remember("nur", List.of(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));

        // Same visitor, a forked branch — a different conversation key entirely.
        agent.handle(new MemoryScope("nur", "nur/experiment"), Persona.NONE, "hello", null);

        assertThat(client.last.messages()).extracting(Message::content)
                .anyMatch(content -> content.contains("name: Nur"));
        // ...and it is not everybody's memory: a different visitor sees none of it.
        agent.handle(MemoryScope.of("someone-else"), Persona.NONE, "hello", null);
        assertThat(client.last.messages()).extracting(Message::content)
                .noneMatch(content -> content.contains("name: Nur"));
    }

    @Test
    void forgettingOneEntryLeavesTheRestOfTheLayerAlone() {
        longTerm.remember("c1", List.of(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur"),
                new LongTermMemory.Entry(LongTermKind.KNOWLEDGE, "project", "meetupper")));

        longTerm.forget("c1", "profile:name");

        assertThat(agent.recall("c1").entries())
                .extracting(LongTermMemory.Entry::key).containsExactly("project");
    }

    @Test
    void theLongTermBlockIsPricedAsItsOwnSegmentOfTheBudget() {
        long without = agent.handle(C1, Persona.NONE, "hello", null).budget().longTermTokens();
        longTerm.remember("c1", List.of(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));
        ContextBudget with = agent.handle(C1, Persona.NONE, "hello again", null).budget();

        // Attributable to a layer rather than lost in "context" — which is what makes the
        // question "why is this prompt expensive?" answerable.
        assertThat(without).isZero();
        assertThat(with.longTermTokens()).isPositive();
        assertThat(with.workingTokens()).isPositive();
        assertThat(with.longTermTokens()).isNotEqualTo(with.workingTokens());
    }

    // --- routing and the task boundary ----------------------------------------------------

    @Test
    void oneExtractionCallFeedsBothLayersAndTheTagDecidesWhichOne() {
        // The day's requirement, end to end: the model labels, the router files. Nothing between
        // the model and the stores gets to have an opinion about lifetimes.
        client.extraction = """
                profile/name: Nur
                task/database: Postgres 16
                knowledge/project: meetupper""";

        agent.handle(C1, Persona.NONE, "I'm Nur, working on meetupper, we're using Postgres 16", null);

        assertThat(client.extractions).isEqualTo(1);
        assertThat(agent.facts("c1").entries())
                .containsExactly(new Facts.Fact("database", "Postgres 16", false));
        assertThat(agent.recall("c1").entries()).containsExactly(
                new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur"),
                new LongTermMemory.Entry(LongTermKind.KNOWLEDGE, "project", "meetupper"));
    }

    @Test
    void aDecisionIsHeldInWorkingMemoryUntilTheTaskThatSettledItIsClosed() {
        // Writing it to long-term immediately would leak it into every branch, including the
        // forks that exist precisely to disagree with it.
        client.extraction = "decision/database: Postgres 16";
        agent.handle(C1, Persona.NONE, "right, we're going with Postgres 16", null);

        assertThat(agent.recall("c1").isPresent()).isFalse();
        assertThat(agent.facts("c1").settled())
                .containsExactly(new Facts.Fact("database", "Postgres 16", true));

        LongTermMemory kept = agent.finishTask(C1).longTerm();

        assertThat(kept.of(LongTermKind.DECISION)).containsExactly(
                new LongTermMemory.Entry(LongTermKind.DECISION, "database", "Postgres 16"));
        // And the scratch it was sitting next to is gone, because the task it belonged to is.
        assertThat(agent.facts("c1").isPresent()).isFalse();
    }

    @Test
    void closingATaskKeepsWhatWasAgreedAndThrowsTheScratchAway() {
        client.extraction = """
                decision/database: Postgres 16
                task/scratch: still deciding the hosting""";
        agent.handle(C1, Persona.NONE, "Postgres 16 it is, still thinking about hosting", null);

        agent.finishTask(C1);

        assertThat(agent.recall("c1").entries())
                .extracting(LongTermMemory.Entry::key).containsExactly("database");
    }

    @Test
    void closingATaskDoesNotTouchTheDialogueOrTheVisitor() {
        // Three layers, three lifetimes. The task boundary ends exactly one of them.
        longTerm.remember("c1", List.of(new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));
        threeTurns(agent);

        agent.finishTask(C1);

        assertThat(agent.transcript("c1")).hasSize(6);
        assertThat(agent.recall("c1").of(LongTermKind.PROFILE)).hasSize(1);
        assertThat(agent.facts("c1").isPresent()).isFalse();
    }

    @Test
    void startingANewTaskClearsTheScratchWithoutPromotingAnything() {
        // The difference between the two buttons: one graduates the agreed lines, the other
        // abandons them. Abandoning has to be possible, or a task that went nowhere gets to
        // write its dead ends into permanent memory.
        client.extraction = "decision/database: Postgres 16";
        agent.handle(C1, Persona.NONE, "let's say Postgres 16", null);

        agent.newTask(C1);

        assertThat(agent.facts("c1").isPresent()).isFalse();
        assertThat(agent.recall("c1").isPresent()).isFalse();
        assertThat(agent.transcript("c1")).hasSize(2);
    }

    @Test
    void theModelProposesTheMoveAndTheNextPromptIsBuiltFromWhereItLanded() {
        // The hybrid in full: the model says where the job has got to, the transition table decides
        // whether it may, and only then does the block exist for the following turn to read. A
        // model that wrote the state directly would be free to declare itself finished.
        client.extraction = """
                stage/stage: execution
                stage/step: writing the migration script
                stage/next: review the script
                stage/waiting: user""";
        agent.handle(C1, Persona.NONE, "right, start on the migration", null);

        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(agent.task("c1").waitingOnUser()).isTrue();

        client.extraction = "none/: nothing to keep";
        agent.handle(C1, Persona.NONE, "and?", null);

        // Carried into the very next call, which is the whole point: it is the thing that makes
        // the model pick up where it stopped rather than asking what it was doing.
        assertThat(client.last.messages()).extracting(Message::content)
                .anyMatch(content -> content.contains("stage: execution")
                        && content.contains("writing the migration script"));
    }

    @Test
    void aStageTheTableForbidsIsRefusedAndTheJobStaysWhereItWas() {
        // The extractor is told not to do this, but "told not to" is not a guarantee — it is a
        // prompt. The table is the guarantee. Refusing costs one turn of an under-claimed stage;
        // accepting would let a sentence promote itself into finished work.
        client.extraction = "stage/stage: done";
        agent.handle(C1, Persona.NONE, "I think that wraps it up", null);

        assertThat(agent.task("c1").isPresent()).isFalse();
        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.PLANNING);
    }

    @Test
    void aPausedTaskRefusesTheTurnOutrightRatherThanAnsweringAndDecliningQuietly() {
        // With no loop and no tools, answering is the only thing this agent does — so a pause that
        // still answers pauses nothing the user can see. The refusal names the stage and the step
        // so the page can offer the way back instead of reporting a dead end.
        agent.moveTask(C1, TaskStage.EXECUTION);
        agent.pauseTask(C1);
        int callsBefore = client.calls;
        int extractionsBefore = client.extractions;

        assertThatThrownBy(() -> agent.handle(C1, Persona.NONE, "how far did we get?", null))
                .isInstanceOf(TaskPausedException.class)
                .hasMessageContaining("paused in execution");

        // Refused before a byte leaves the process, and before the extractor call that rides along
        // with every turn. A pause that still pays for two calls is a pause that saves nothing.
        assertThat(client.calls).isEqualTo(callsBefore);
        assertThat(client.extractions).isEqualTo(extractionsBefore);
        // And nothing was recorded, so the transcript does not gain a question that was never asked.
        assertThat(agent.transcript("c1")).isEmpty();
        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(agent.task("c1").paused()).isTrue();
    }

    @Test
    void resumingLetsTheVeryNextMessageLandWithoutReplayingTheRefusedOne() {
        agent.moveTask(C1, TaskStage.EXECUTION);
        agent.pauseTask(C1);
        assertThatThrownBy(() -> agent.handle(C1, Persona.NONE, "how far did we get?", null))
                .isInstanceOf(TaskPausedException.class);

        agent.resumeTask(C1);
        client.extraction = "stage/stage: validation";
        agent.handle(C1, Persona.NONE, "carry on", null);

        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.VALIDATION);
        assertThat(agent.task("c1").paused()).isFalse();
        // The refused message is not resurrected — "carry on" is the first thing the model ever saw.
        assertThat(agent.transcript("c1")).extracting(Message::content)
                .containsExactly("carry on", "reply 1");
    }

    @Test
    void anAsideIsAnsweredWhilePausedAndLeavesTheTaskExactlyWhereItWas() {
        // A pause stops the work, not the conversation. Refusing every message would also refuse
        // "what did we decide about the deadline?", which moves nothing and is not a resume.
        client.extraction = """
                stage/stage: execution
                stage/step: drafting the migration""";
        agent.handle(C1, Persona.NONE, "start on the migration", null);
        agent.pauseTask(C1);

        // The model tries to advance on the way past, as it would on any turn.
        client.extraction = "stage/stage: validation";
        AgentResult result = agent.handle(C1, Persona.NONE, "unrelated: what is our deadline?",
                null, PausedTurn.ASIDE);

        assertThat(result.answer()).isNotBlank();
        // Answered and remembered — an aside is a real turn, which is the honest cost of it.
        assertThat(agent.transcript("c1")).extracting(Message::content)
                .endsWith("unrelated: what is our deadline?", result.answer());
        // But the machine did not move, and is still paused, so Resume still lands where it was.
        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(agent.task("c1").step()).isEqualTo("drafting the migration");
        assertThat(agent.task("c1").paused()).isTrue();
    }

    @Test
    void anAsideOnAnUnpausedTaskIsJustAnOrdinaryTurn() {
        // The flag says what to do about a pause, not whether to freeze one — a task that is
        // running must not be quietly held still by which button happened to be clicked.
        agent.moveTask(C1, TaskStage.EXECUTION);
        client.extraction = "stage/stage: validation";

        agent.handle(C1, Persona.NONE, "carry on", null, PausedTurn.ASIDE);

        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.VALIDATION);
    }

    @Test
    void aConversationWithNoTaskIsNeverPausedAndIsNotAffectedByTheGate() {
        // The pause gate keys off a task that exists. A dialogue that never started one is the
        // pre-machine flow, and it must not become unusable because a flag defaults to false.
        agent.pauseTask(C1);

        assertThat(agent.task("c1").isPresent()).isFalse();
        assertThat(agent.handle(C1, Persona.NONE, "hello", null).answer()).isNotBlank();
    }

    @Test
    void theExtractorIsShownWhereTheJobIsSoItCanTellWhetherTheMessageMovedIt() {
        // Asked to propose a transition with no idea of the current state, the only honest answer
        // is a guess. The brief carries the state for the same reason it carries the keys in use.
        agent.moveTask(C1, TaskStage.EXECUTION);
        agent.handle(C1, Persona.NONE, "done with the script", null);

        assertThat(client.lastExtraction.messages().get(1).content()).contains("stage: execution");
        // And the legal moves, so a refusal is a bug in the model rather than a gap in the brief.
        assertThat(client.lastExtraction.messages().get(0).content()).contains("execution");
    }

    @Test
    void aTaskCanOnlyBeClosedFromAStageThatLeadsToDone() {
        // Finishing is a transition, not a label, so the button is bound by the same table as the
        // model. Promoting from planning would write "what was agreed" out of a job where nothing
        // has been built, let alone checked.
        client.extraction = "decision/database: Postgres 16";
        agent.handle(C1, Persona.NONE, "let's say Postgres 16", null);
        agent.moveTask(C1, TaskStage.EXECUTION);

        Agent.TaskClosure refused = agent.finishTask(C1);

        assertThat(refused.closed()).isFalse();
        assertThat(refused.why()).contains("execution").contains("done");
        // Nothing moved and nothing was promoted — a refused close is not a partial one.
        assertThat(agent.task("c1").stage()).isEqualTo(TaskStage.EXECUTION);
        assertThat(agent.recall("c1").isPresent()).isFalse();
        assertThat(agent.facts("c1").isPresent()).isTrue();

        agent.moveTask(C1, TaskStage.VALIDATION);
        Agent.TaskClosure closed = agent.finishTask(C1);

        assertThat(closed.closed()).isTrue();
        assertThat(closed.longTerm().entries()).extracting(LongTermMemory.Entry::key)
                .containsExactly("database");
        assertThat(agent.facts("c1").isPresent()).isFalse();
        assertThat(agent.task("c1").isPresent()).isFalse();
    }

    @Test
    void aConversationThatNeverStartedATaskCanStillBeClosed() {
        // The machine governs jobs that have a state. One that never had a stage is the flow that
        // existed before the machine did, and adding the machine must not take the button away.
        client.extraction = "decision/database: Postgres 16";
        agent.handle(C1, Persona.NONE, "let's say Postgres 16", null);

        Agent.TaskClosure closure = agent.finishTask(C1);

        assertThat(closure.closed()).isTrue();
        assertThat(closure.longTerm().entries()).hasSize(1);
    }

    @Test
    void startingANewTaskResetsTheMachineAsWellAsTheScratch() {
        // Abandoning is not a transition — done is where a job ends, not where it is dropped. The
        // reset has to clear the stage too, or the next job inherits the last one's position.
        agent.moveTask(C1, TaskStage.VALIDATION);

        agent.newTask(C1);

        assertThat(agent.task("c1")).isEqualTo(TaskState.EMPTY);
        assertThat(agent.task("c1").isPresent()).isFalse();
    }

    @Test
    void anUnroutableTagCostsThatLineAndNothingElse() {
        client.extraction = """
                personal/name: Nur
                task/database: Postgres 16""";

        AgentResult result = agent.handle(C1, Persona.NONE, "I'm Nur and we use Postgres 16", null);

        assertThat(result.answer()).isNotBlank();
        assertThat(agent.recall("c1").isPresent()).isFalse();
        assertThat(agent.facts("c1").entries()).extracting(Facts.Fact::key).containsExactly("database");
    }

    @Test
    void theExtractorIsToldWhatEveryLayerCallsThingsSoACorrectionCanLandOnTheSameKey() {
        // Observed against the real model: told nothing, it answered "Postgres 16 it is, final"
        // with `postgres: 16` while `database: Postgres 16` was already held — two keys for one
        // subject, both true, one of them stale. Names are shared across the layers because the
        // model is being told what a subject is called, not where it lives.
        longTerm.remember("c1", List.of(new LongTermMemory.Entry(LongTermKind.PROFILE, "name", "Nur")));
        client.extraction = "task/database: Postgres 16";
        agent.handle(C1, Persona.NONE, "we'll use Postgres 16", null);

        agent.handle(C1, Persona.NONE, "actually 15", null);

        String brief = client.lastExtraction.messages().get(1).content();
        assertThat(brief).contains("KEYS IN USE:").contains("database").contains("name");
        // The values stay out: shown them, the model re-emits standing facts, and the turn it
        // re-emits only some of them is the turn the rest go missing.
        assertThat(brief).doesNotContain("Postgres 16");
    }

    @Test
    void aTurnThatOnlyTaughtLongTermMemoryIsStillOnTheWorkingBlocksBill() {
        // One call now feeds two layers, and working memory is the only layer counting. A call
        // charged to nobody is a call nobody notices the cost of.
        client.extraction = "profile/name: Nur";

        agent.handle(C1, Persona.NONE, "I'm Nur", null);

        assertThat(agent.facts("c1").buildTokens()).isEqualTo(60);
        // ...but the block itself did not move, so the revision counter still means what it says.
        assertThat(agent.facts("c1").revision()).isZero();
    }

    @Test
    void aProfilePreferenceLeavesAsARequestParameterNotAsPolite() {
        // The difference between this and writing "keep it short" in the prompt: the model cannot
        // decline a ceiling. 400 is what the file asked for, so 400 is what the provider is told.
        agent.handle(C1, Persona.of(RUSSELL), "hello", null);

        assertThat(client.last.maxCompletionTokens()).isEqualTo(400);
        assertThat(client.last.temperature()).isEqualTo(0.2);
    }

    @Test
    void whatThePagePutsInTheBoxStillBeatsTheProfile() {
        // Three tiers: explicit value, then profile, then yml default. The page has to come first
        // or the settings panel would be decoration for anybody who owns a profile.
        agent.handle(C1, Persona.of(RUSSELL), "hello",
                AgentConfig.builder().maxCompletionTokens(64).build());

        assertThat(client.last.maxCompletionTokens()).isEqualTo(64);
        // Untouched by the page, so the profile still fills it — one field overridden, not all.
        assertThat(client.last.temperature()).isEqualTo(0.2);
    }

    @Test
    void whatTheProfileLeavesUnsetFallsThroughToTheYmlDefault() {
        UserProfile quiet = new UserProfile("q", "Q", "English", "plain",
                UserProfile.Format.NONE, List.of(), Limits.NONE, null);

        agent.handle(C1, Persona.of(quiet), "hello", null);

        assertThat(client.last.maxCompletionTokens()).isEqualTo(256);
        assertThat(client.last.temperature()).isEqualTo(1.0);
    }

    @Test
    void theProfileIsSentAsItsOwnSystemBlockUnderTheSystemPrompt() {
        agent.handle(C1, Persona.of(RUSSELL), "hello", null);

        assertThat(client.last.messages().get(0)).isEqualTo(Message.system("be brief"));
        assertThat(client.last.messages().get(1).role()).isEqualTo("system");
        assertThat(client.last.messages().get(1).content())
                .contains("Russell")
                .contains("Answer in: Russian")
                .contains("short bullet points, at most 120 words")
                .contains("use emoji");
    }

    @Test
    void aLensNarrowsTheProfileItIsWornOverWithoutReplacingIt() {
        Lens chemist = new Lens("chemist", "Chemist", "Answer as a chemist.",
                List.of("give synthesis procedures"), List.of("реакц"), new Limits(null, 0.9, null));

        // The user's limit wins where both speak — a lens changes the subject, not the person's
        // standing preferences — but the lens still fills what the user left open.
        agent.handle(C1, new Persona(RUSSELL, chemist), "hello", null);

        assertThat(client.last.temperature()).isEqualTo(0.2);
        assertThat(client.last.messages().get(1).content())
                .contains("Russell")
                .contains("Answer as a chemist.")
                .contains("give synthesis procedures");
    }

    @Test
    void theProfileIsPricedOnTheAnswersBudget() {
        long without = agent.handle(C1, Persona.NONE, "hello", null).budget().profileTokens();
        long with = agent.handle(C1, Persona.of(RUSSELL), "hello again", null).budget().profileTokens();

        assertThat(without).isZero();
        assertThat(with).isPositive();
    }

    @Test
    void thePageIsToldTheProfilesNumbersSoItsBoxesDoNotOverrideThem() {
        // The settings panel prefills from this and posts the same values straight back. Handed
        // the raw defaults it would return them as an explicit override on every turn, and the
        // enforced half of a profile would be dead in the browser while still passing every unit
        // test that calls the agent directly. That is exactly what happened.
        AgentConfig shown = agent.effectiveConfig(Persona.of(RUSSELL), null);

        assertThat(shown.maxCompletionTokens()).isEqualTo(400);
        assertThat(shown.temperature()).isEqualTo(0.2);
        // Posting it back unchanged has to be a no-op, not an override.
        assertThat(agent.effectiveConfig(Persona.of(RUSSELL), shown)).isEqualTo(shown);
        // What the profile says nothing about still comes from the yml.
        assertThat(shown.model()).isEqualTo("openai/gpt-oss-20b");
        assertThat(shown.systemPrompt()).isEqualTo("be brief");
    }

    @Test
    void theQuotedBudgetPricesTheProfileTheAnswerWouldActuallyUse() {
        // The page asks for the budget before anything is sent. Quoting it without the persona
        // would understate every personalized turn by the size of the block.
        ContextBudget quoted = agent.budget(C1, Persona.of(RUSSELL), null);

        assertThat(quoted.profileTokens()).isPositive();
        assertThat(quoted.reservedCompletionTokens()).isEqualTo(400);
    }

    private Agent compressingAgent() {
        return newAgent(20, planner(131_072, OverflowPolicy.FAIL), properties(4));
    }

    /** Enough turns to put four messages behind the two-message verbatim tail. */
    private AgentResult fourTurns(Agent target) {
        threeTurns(target);
        return target.handle(C1, Persona.NONE, "what is my name?", null);
    }

    private void threeTurns(Agent target) {
        target.handle(C1, Persona.NONE, "my name is Nur", null);
        target.handle(C1, Persona.NONE, "I work on meetupper", null);
        target.handle(C1, Persona.NONE, "it is a Spring app", null);
    }

    /** The same four turns, at the length a real message actually runs to. */
    private AgentResult wordyDialogue(Agent target) {
        target.handle(C1, Persona.NONE, "my name is Nur and I am building an agent in Spring Boot "
                + "that talks to Groq models", null);
        target.handle(C1, Persona.NONE, "it keeps a transcript and replays every word of it on each "
                + "turn, which gets expensive", null);
        target.handle(C1, Persona.NONE, "today I am adding compression so the old turns become notes "
                + "instead of whole messages", null);
        return target.handle(C1, Persona.NONE, "what is my name?", null);
    }

    /** Keep the last 2 messages verbatim and fold once {@code compressEvery} pile up behind them. */
    private static AgentProperties properties(int compressEvery) {
        return new AgentProperties("test-key", "https://example.invalid",
                Duration.ofSeconds(1), Duration.ofSeconds(1), List.of("openai/gpt-oss-20b"),
                List.of("", "low", "medium", "high"),
                new AgentProperties.Defaults("openai/gpt-oss-20b", "be brief", 1.0, 256,
                        "", List.of(), ""),
                new AgentProperties.Limit(100), new AgentProperties.Limit(0),
                new AgentProperties.Memory(20, "memory", "", ""),
                new AgentProperties.Context(Map.of(), 131_072, OverflowPolicy.FAIL, 0.8),
                new AgentProperties.Compression(2, compressEvery, "summarizer", 120, "low"),
                new AgentProperties.FactMemory("extractor", 12, 600, "low"),
                new AgentProperties.LongTerm("", 24),
                new AgentProperties.Personalization(""));
    }

    /**
     * Summarization goes through the same client as a user turn, so the double is told apart by
     * model id — which is also the cheapest proof that compression really is an extra billed call.
     */
    private static final class ScriptedClient implements LlmClient {
        private static final String SUMMARIZER = "summarizer";
        private static final String EXTRACTOR = "extractor";

        private ChatRequest last;
        private ChatRequest lastSummarization;
        private ChatRequest lastExtraction;
        private int summarizations;
        private int extractions;
        private boolean failNext;
        private boolean failSummarization;
        private boolean failExtraction;
        private String extraction;
        private String finishReason = "stop";
        private int calls;

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (EXTRACTOR.equals(request.model())) {
                lastExtraction = request;
                extractions++;
                if (failExtraction) {
                    throw new LlmException("extractor unavailable");
                }
                // A fresh key every call, so each turn genuinely moves the block on. Tagged,
                // because the extractor no longer produces bare facts — the tag is what the
                // router reads, and a double that omitted it would test a path nothing uses.
                return new ChatResponse(extraction != null ? extraction
                        : "task/fact" + extractions + ": value " + extractions,
                        request.model(), "stop", new TokenUsage(40, 20, 60));
            }
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
