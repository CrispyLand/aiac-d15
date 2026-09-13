package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crispyland.agent.memory.Facts;
import com.crispyland.agent.memory.MemoryState;
import com.crispyland.agent.memory.Message;
import com.crispyland.agent.memory.Summary;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.OverflowPolicy;
import com.crispyland.agent.usage.TemplateOverhead;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextPlannerTest {

    /** SUMMARY replays the whole retained transcript, which is the baseline the pricing tests want. */
    private static final AgentConfig CONFIG = AgentConfig.builder()
            .model("openai/gpt-oss-20b")
            .systemPrompt("be brief")
            .maxCompletionTokens(100)
            .contextStrategy(ContextStrategy.SUMMARY)
            .build();

    private static AgentConfig using(ContextStrategy strategy) {
        return CONFIG.toBuilder().contextStrategy(strategy).build();
    }

    private static ContextPlanner planner(int window, OverflowPolicy policy) {
        return planner(window, policy, 4);
    }

    private static ContextPlanner planner(int window, OverflowPolicy policy, int windowMessages) {
        return new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(), Map.of(),
                window, policy, 0.8, windowMessages);
    }

    @Test
    void theSegmentsAddUpToTheEstimatedPrompt() {
        ContextBudget budget = planner(1000, OverflowPolicy.FAIL)
                .plan(CONFIG, MemoryState.of(history(2)), "hello").budget();

        assertThat(budget.promptTokens()).isEqualTo(budget.systemTokens() + budget.summaryTokens()
                + budget.historyTokens() + budget.inputTokens() + budget.overheadTokens());
        assertThat(budget.projectedTokens()).isEqualTo(budget.promptTokens() + 100);
        assertThat(budget.remainingTokens()).isEqualTo(1000 - budget.projectedTokens());
    }

    @Test
    void theProvidersTemplateCostIsLearnedFromTheFirstResponse() {
        TemplateOverhead overhead = new TemplateOverhead();
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), overhead, Map.of(),
                1000, OverflowPolicy.OFF, 0.8, 4);

        ContextBudget first = planner.plan(CONFIG, MemoryState.EMPTY, "hello").budget();
        assertThat(first.calibrated()).isFalse();
        assertThat(first.overheadTokens()).isZero();

        // Groq reports 60 tokens more than the messages alone encode to: that gap is the
        // harmony template, and it is the same on every subsequent call.
        overhead.observe("openai/gpt-oss-20b", first.countedTokens(), first.countedTokens() + 60);

        ContextBudget second = planner.plan(CONFIG, MemoryState.EMPTY, "hello").budget();
        assertThat(second.calibrated()).isTrue();
        assertThat(second.overheadTokens()).isEqualTo(60);
        assertThat(second.promptTokens()).isEqualTo(first.promptTokens() + 60);
    }

    @Test
    void repeatedObservationsConvergeRatherThanOscillate() {
        TemplateOverhead overhead = new TemplateOverhead();
        for (int i = 0; i < 6; i++) {
            overhead.observe("m", 100, 160);
        }

        assertThat(overhead.forModel("m")).isEqualTo(60);
        assertThat(overhead.forModel("never-seen")).isZero();
    }

    @Test
    void anOverCountIsNeverTurnedIntoADiscount() {
        TemplateOverhead overhead = new TemplateOverhead();
        // Provider billed 80 where 100 were counted: our own count was high, not the template.
        overhead.observe("m", 100, 80);

        assertThat(overhead.forModel("m")).isZero();
        assertThat(overhead.calibrated("m")).isTrue();
    }

    @Test
    void theWindowMustHoldThePromptAndTheReplyTogether() {
        // A prompt that fits on its own but leaves no room for the answer is still a failure —
        // this is the arithmetic a message-counted window cannot see.
        ContextBudget budget = planner(1000, OverflowPolicy.OFF)
                .plan(AgentConfig.builder().model("m").maxCompletionTokens(995)
                                .contextStrategy(ContextStrategy.SUMMARY).build(),
                        MemoryState.EMPTY, "hello").budget();

        assertThat(budget.promptTokens()).isLessThan(1000);
        assertThat(budget.overflowing()).isTrue();
    }

    @Test
    void aPerModelWindowOverridesTheDefault() {
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(),
                Map.of("openai/gpt-oss-20b", 8192), 131_072, OverflowPolicy.OFF, 0.8, 4);

        assertThat(planner.budget(CONFIG, MemoryState.EMPTY).contextWindow()).isEqualTo(8192);
        assertThat(planner.budget(AgentConfig.builder().model("unlisted").build(), MemoryState.EMPTY)
                .contextWindow()).isEqualTo(131_072);
    }

    @Test
    void warningFiresBeforeOverflowNotAtIt() {
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(),
                Map.of(), 130, OverflowPolicy.OFF, 0.8, 4);

        ContextBudget budget = planner.plan(CONFIG, MemoryState.EMPTY, "hello").budget();

        assertThat(budget.overflowing()).isFalse();
        assertThat(budget.warning()).isTrue();
        assertThat(budget.status()).isEqualTo("warn");
    }

    @Test
    void failRefusesTheCallAndExplainsTheArithmetic() {
        assertThatThrownBy(() -> planner(120, OverflowPolicy.FAIL).plan(CONFIG, MemoryState.of(history(10)), "hello"))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("over by")
                .hasMessageContaining("system")
                .hasMessageContaining("history");
    }

    @Test
    void offMeasuresTheOverflowButStillBuildsTheRequest() {
        ContextPlanner.ContextPlan plan = planner(120, OverflowPolicy.OFF).plan(CONFIG, MemoryState.of(history(10)), "hello");

        assertThat(plan.budget().overflowing()).isTrue();
        assertThat(plan.budget().droppedMessages()).isZero();
        assertThat(plan.messages()).hasSize(12);
    }

    @Test
    void trimDropsOldestFirstAndKeepsTheWindowOpeningOnAUserMessage() {
        ContextPlanner.ContextPlan plan = planner(150, OverflowPolicy.TRIM).plan(CONFIG, MemoryState.of(history(10)), "hello");

        assertThat(plan.budget().trimmed()).isTrue();
        assertThat(plan.budget().overflowing()).isFalse();
        assertThat(plan.messages().get(0).role()).isEqualTo("system");
        assertThat(plan.messages().get(1).role()).isEqualTo("user");
        // The tail is kept, the head is dropped — recency is what a dialogue needs.
        assertThat(plan.messages()).extracting(Message::content).contains("assistant 9");
    }

    @Test
    void trimStillFailsWhenTheFixedPartsAloneDoNotFit() {
        // Nothing left to drop: system prompt + new message + reserved reply already overflow.
        assertThatThrownBy(() -> planner(100, OverflowPolicy.TRIM).plan(CONFIG, MemoryState.of(history(10)), "hello"))
                .isInstanceOf(ContextOverflowException.class);
    }

    @Test
    void anEmptyDialogueStillCostsTheSystemPromptAndTheReservedReply() {
        ContextBudget budget = planner(1000, OverflowPolicy.FAIL).budget(CONFIG, MemoryState.EMPTY);

        assertThat(budget.historyTokens()).isZero();
        assertThat(budget.inputTokens()).isZero();
        assertThat(budget.systemTokens()).isPositive();
        assertThat(budget.reservedCompletionTokens()).isEqualTo(100);
    }

    @Test
    void theSummaryIsSentAsItsOwnSegmentAheadOfTheRetainedTurns() {
        Summary summary = Summary.EMPTY.rewrittenAs("user is called Nur", 8, 400, 60);

        ContextPlanner.ContextPlan plan = planner(1000, OverflowPolicy.FAIL)
                .plan(CONFIG, MemoryState.of(summary, history(2)), "hello");

        // System prompt first, then what was forgotten, then what is still remembered verbatim.
        assertThat(plan.messages()).extracting(Message::role)
                .containsExactly("system", "system", "user", "assistant", "user");
        assertThat(plan.messages().get(1).content()).contains("user is called Nur");
        assertThat(plan.budget().summaryTokens()).isPositive();
        // The saving is measured against what those 400 tokens of messages used to cost.
        assertThat(plan.budget().replacedTokens()).isEqualTo(400);
        assertThat(plan.budget().savedTokens())
                .isEqualTo(400 - plan.budget().summaryTokens());
        assertThat(plan.budget().uncompressedPromptTokens())
                .isEqualTo(plan.budget().promptTokens() + plan.budget().savedTokens());
    }

    @Test
    void withoutASummaryNothingAboutTheBudgetChanges() {
        ContextBudget budget = planner(1000, OverflowPolicy.FAIL)
                .plan(CONFIG, MemoryState.of(history(2)), "hello").budget();

        assertThat(budget.compressed()).isFalse();
        assertThat(budget.summaryTokens()).isZero();
        assertThat(budget.savedTokens()).isZero();
        assertThat(budget.uncompressedPromptTokens()).isEqualTo(budget.promptTokens());
    }

    @Test
    void theSlidingWindowSendsOnlyTheTailAndSaysHowMuchItLeftOut() {
        ContextPlanner.ContextPlan plan = planner(1000, OverflowPolicy.FAIL, 4)
                .plan(using(ContextStrategy.SLIDING_WINDOW), MemoryState.of(history(10)), "hello");

        // system + 4 replayed + the new message. Nothing stands in for the other six.
        assertThat(plan.messages()).hasSize(6);
        assertThat(plan.messages()).extracting(Message::content)
                .containsSequence("user 6", "assistant 7", "user 8", "assistant 9");
        assertThat(plan.budget().windowedMessages()).isEqualTo(6);
        assertThat(plan.budget().windowed()).isTrue();
        // Left out is not the same as trimmed: nothing overflowed, this is the plan working.
        assertThat(plan.budget().trimmed()).isFalse();
    }

    @Test
    void theWindowNeverOpensOnAnAssistantReply() {
        // A window of 5 would start at index 5 — "assistant 5" — so it gives one up to start
        // on "user 6" instead. A replay beginning mid-turn reads as though the agent spoke first.
        ContextPlanner.ContextPlan plan = planner(1000, OverflowPolicy.FAIL, 5)
                .plan(using(ContextStrategy.SLIDING_WINDOW), MemoryState.of(history(10)), "hello");

        assertThat(plan.messages().get(1).content()).isEqualTo("user 6");
        assertThat(plan.budget().windowedMessages()).isEqualTo(6);
    }

    @Test
    void theSummaryStrategyIgnoresTheWindowBecauseItsStoreIsAlreadyFolded() {
        ContextPlanner.ContextPlan plan = planner(1000, OverflowPolicy.FAIL, 4)
                .plan(CONFIG, MemoryState.of(history(10)), "hello");

        assertThat(plan.messages()).hasSize(12);
        assertThat(plan.budget().windowedMessages()).isZero();
    }

    @Test
    void factsAreSentAheadOfTheWindowedTailAndPricedOnTheirOwn() {
        Facts facts = Facts.EMPTY.updatedWith(List.of(
                new Facts.Fact("database", "Postgres 16"),
                new Facts.Fact("deadline", "end of Q3")), 12, 90);

        ContextPlanner.ContextPlan plan = planner(1000, OverflowPolicy.FAIL, 4)
                .plan(using(ContextStrategy.STICKY_FACTS), new MemoryState(Summary.EMPTY, facts, history(10)),
                        "hello");

        assertThat(plan.messages()).extracting(Message::role)
                .containsExactly("system", "system", "user", "assistant", "user", "assistant", "user");
        assertThat(plan.messages().get(1).content()).contains("database: Postgres 16");
        assertThat(plan.budget().factsTokens()).isPositive();
        assertThat(plan.budget().summaryTokens()).isZero();
        assertThat(plan.budget().promptTokens()).isEqualTo(plan.budget().systemTokens()
                + plan.budget().factsTokens() + plan.budget().historyTokens()
                + plan.budget().inputTokens() + plan.budget().overheadTokens());
    }

    @Test
    void aStrategyOnlyReadsItsOwnMemoryEvenWhenBothArePresent() {
        // The store may hold notes from a previous stint on the Summary tab. Reading them from
        // the Facts tab would make the two tabs incomparable — and would charge twice for the
        // same past. Each tab sees exactly the memory it maintains.
        MemoryState both = new MemoryState(
                Summary.EMPTY.rewrittenAs("they argued about databases", 8, 400, 60),
                Facts.EMPTY.updatedWith(List.of(new Facts.Fact("database", "Postgres 16")), 12, 90),
                history(2));

        ContextBudget asFacts = planner(1000, OverflowPolicy.FAIL)
                .plan(using(ContextStrategy.STICKY_FACTS), both, "hello").budget();
        assertThat(asFacts.summaryTokens()).isZero();
        assertThat(asFacts.replacedTokens()).isZero();
        assertThat(asFacts.factsTokens()).isPositive();

        ContextBudget asWindow = planner(1000, OverflowPolicy.FAIL)
                .plan(using(ContextStrategy.SLIDING_WINDOW), both, "hello").budget();
        assertThat(asWindow.summaryTokens()).isZero();
        assertThat(asWindow.factsTokens()).isZero();
    }

    @Test
    void anIdIsWhatTheTabsRoundTripThroughAndGarbageFallsBackToTheDefault() {
        assertThat(ContextStrategy.SLIDING_WINDOW.id()).isEqualTo("sliding-window");
        assertThat(ContextStrategy.from("sliding-window")).isEqualTo(ContextStrategy.SLIDING_WINDOW);
        assertThat(ContextStrategy.from("STICKY_FACTS")).isEqualTo(ContextStrategy.STICKY_FACTS);
        assertThat(ContextStrategy.from("  summary ")).isEqualTo(ContextStrategy.SUMMARY);
        // Null rather than a throw: a hand-edited query string must not be able to 400 the page.
        assertThat(ContextStrategy.from("nonsense")).isNull();
        assertThat(ContextStrategy.from("")).isNull();
    }

    /** {@code count} messages alternating user/assistant, as a real transcript would be. */
    private static List<Message> history(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(i % 2 == 0 ? Message.user("user " + i) : Message.assistant("assistant " + i));
        }
        return messages;
    }
}
