package com.crispyland.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crispyland.agent.memory.Message;
import com.crispyland.agent.usage.BpeTokenCounter;
import com.crispyland.agent.usage.ContextBudget;
import com.crispyland.agent.usage.OverflowPolicy;
import com.crispyland.agent.usage.TemplateOverhead;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextPlannerTest {

    private static final AgentConfig CONFIG = AgentConfig.builder()
            .model("openai/gpt-oss-20b")
            .systemPrompt("be brief")
            .maxCompletionTokens(100)
            .build();

    private static ContextPlanner planner(int window, OverflowPolicy policy) {
        return new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(), Map.of(),
                window, policy, 0.8);
    }

    @Test
    void theSegmentsAddUpToTheEstimatedPrompt() {
        ContextBudget budget = planner(1000, OverflowPolicy.FAIL)
                .plan(CONFIG, history(2), "hello").budget();

        assertThat(budget.promptTokens()).isEqualTo(budget.systemTokens() + budget.historyTokens()
                + budget.inputTokens() + budget.overheadTokens());
        assertThat(budget.projectedTokens()).isEqualTo(budget.promptTokens() + 100);
        assertThat(budget.remainingTokens()).isEqualTo(1000 - budget.projectedTokens());
    }

    @Test
    void theProvidersTemplateCostIsLearnedFromTheFirstResponse() {
        TemplateOverhead overhead = new TemplateOverhead();
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), overhead, Map.of(),
                1000, OverflowPolicy.OFF, 0.8);

        ContextBudget first = planner.plan(CONFIG, List.of(), "hello").budget();
        assertThat(first.calibrated()).isFalse();
        assertThat(first.overheadTokens()).isZero();

        // Groq reports 60 tokens more than the messages alone encode to: that gap is the
        // harmony template, and it is the same on every subsequent call.
        overhead.observe("openai/gpt-oss-20b", first.countedTokens(), first.countedTokens() + 60);

        ContextBudget second = planner.plan(CONFIG, List.of(), "hello").budget();
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
                .plan(AgentConfig.builder().model("m").maxCompletionTokens(995).build(),
                        List.of(), "hello").budget();

        assertThat(budget.promptTokens()).isLessThan(1000);
        assertThat(budget.overflowing()).isTrue();
    }

    @Test
    void aPerModelWindowOverridesTheDefault() {
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(),
                Map.of("openai/gpt-oss-20b", 8192), 131_072, OverflowPolicy.OFF, 0.8);

        assertThat(planner.budget(CONFIG, List.of()).contextWindow()).isEqualTo(8192);
        assertThat(planner.budget(AgentConfig.builder().model("unlisted").build(), List.of())
                .contextWindow()).isEqualTo(131_072);
    }

    @Test
    void warningFiresBeforeOverflowNotAtIt() {
        ContextPlanner planner = new ContextPlanner(new BpeTokenCounter(), new TemplateOverhead(),
                Map.of(), 130, OverflowPolicy.OFF, 0.8);

        ContextBudget budget = planner.plan(CONFIG, List.of(), "hello").budget();

        assertThat(budget.overflowing()).isFalse();
        assertThat(budget.warning()).isTrue();
        assertThat(budget.status()).isEqualTo("warn");
    }

    @Test
    void failRefusesTheCallAndExplainsTheArithmetic() {
        assertThatThrownBy(() -> planner(120, OverflowPolicy.FAIL).plan(CONFIG, history(10), "hello"))
                .isInstanceOf(ContextOverflowException.class)
                .hasMessageContaining("over by")
                .hasMessageContaining("system")
                .hasMessageContaining("history");
    }

    @Test
    void offMeasuresTheOverflowButStillBuildsTheRequest() {
        ContextPlanner.ContextPlan plan = planner(120, OverflowPolicy.OFF).plan(CONFIG, history(10), "hello");

        assertThat(plan.budget().overflowing()).isTrue();
        assertThat(plan.budget().droppedMessages()).isZero();
        assertThat(plan.messages()).hasSize(12);
    }

    @Test
    void trimDropsOldestFirstAndKeepsTheWindowOpeningOnAUserMessage() {
        ContextPlanner.ContextPlan plan = planner(150, OverflowPolicy.TRIM).plan(CONFIG, history(10), "hello");

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
        assertThatThrownBy(() -> planner(100, OverflowPolicy.TRIM).plan(CONFIG, history(10), "hello"))
                .isInstanceOf(ContextOverflowException.class);
    }

    @Test
    void anEmptyDialogueStillCostsTheSystemPromptAndTheReservedReply() {
        ContextBudget budget = planner(1000, OverflowPolicy.FAIL).budget(CONFIG, List.of());

        assertThat(budget.historyTokens()).isZero();
        assertThat(budget.inputTokens()).isZero();
        assertThat(budget.systemTokens()).isPositive();
        assertThat(budget.reservedCompletionTokens()).isEqualTo(100);
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
