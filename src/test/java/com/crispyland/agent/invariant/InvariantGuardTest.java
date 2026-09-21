package com.crispyland.agent.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import com.crispyland.agent.llm.ChatRequest;
import com.crispyland.agent.llm.ChatResponse;
import com.crispyland.agent.llm.LlmClient;
import com.crispyland.agent.llm.LlmException;
import com.crispyland.agent.usage.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guard's job is to be cheap when it can be and conservative when it cannot. Both halves are
 * tested by counting calls as much as by reading rulings — a guard that refuses correctly but pays
 * a call to do it has failed at the thing that makes it affordable.
 */
class InvariantGuardTest {

    private final ScriptedGuard client = new ScriptedGuard();
    private final InvariantGuard guard = new InvariantGuard(client, "guard", 400, "low");

    private static Invariant rule(String id, Check check, List<String> watch) {
        return new Invariant(id, InvariantKind.STACK, InvariantScope.GLOBAL, check,
                "Do not propose a datastore other than Postgres.",
                "one ops surface, and nobody here has run Mongo in production",
                "use Postgres — an unlogged table or a materialized view for caching",
                watch, true, "");
    }

    @Test
    void aForbiddenTermIsSettledInJavaWithoutSpendingACall() {
        // The whole reason for the tiering: the commonest shape of rule people actually write down
        // costs nothing to enforce, so the budget is free for the rules that genuinely need a model.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.FORBID, List.of("mongo")));

        InvariantGuard.Ruling ruling = guard.check("let's store the sessions in Mongo", rules);

        assertThat(ruling.breached()).isTrue();
        assertThat(ruling.settledInJava()).isTrue();
        assertThat(ruling.terms()).containsExactly("mongo");
        assertThat(ruling.costTokens()).isZero();
        assertThat(client.calls).isZero();
    }

    @Test
    void aWatchedTermIsPutToTheModelRatherThanRefusedOnSight() {
        // "Can we drop Redis?" names the thing a rule is about and breaks nothing. That distinction
        // is exactly what the paid tier buys, so the call has to actually happen here.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.WATCH, List.of("mongo")));
        client.reply = "ok";

        InvariantGuard.Ruling ruling = guard.check("why did we rule Mongo out again?", rules);

        assertThat(ruling.breached()).isFalse();
        assertThat(client.calls).isEqualTo(1);
        assertThat(ruling.costTokens()).isEqualTo(36);
    }

    @Test
    void aWatchedRuleTheModelNamesIsRefusedWithTheCostOfFindingOut() {
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.WATCH, List.of("mongo")));
        client.reply = "inv-1: this would put the session store on Mongo.";

        InvariantGuard.Ruling ruling = guard.check("move the sessions over to Mongo", rules);

        assertThat(ruling.breached()).isTrue();
        // Not settled in Java: the page says so, and the difference is a call on the bill.
        assertThat(ruling.settledInJava()).isFalse();
        assertThat(ruling.costTokens()).isEqualTo(36);
        assertThat(ruling.broken().id()).isEqualTo("inv-1");
    }

    @Test
    void aWatchedRuleWhoseTermsAreAbsentIsNotEvenAskedAbout() {
        // The saving that makes the middle tier viable: a turn that comes nowhere near a rule pays
        // nothing for it, so the guard's cost tracks how often rules are approached, not turn count.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.WATCH, List.of("mongo")));

        InvariantGuard.Ruling ruling = guard.check("what did we decide about the retry policy?", rules);

        assertThat(ruling.breached()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void aRuleWithNothingToSearchForIsAskedAboutOnEveryTurn() {
        // "Never quote below cost" has no term to grep for. It is the expensive tier and it exists
        // because the alternative is a rule enforced by nothing at all.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.MODEL, List.of()));
        client.reply = "ok";

        guard.check("what did we decide about the retry policy?", rules);

        assertThat(client.calls).isEqualTo(1);
        assertThat(client.last.messages().get(1).content()).contains("INV-1");
    }

    @Test
    void aRuleCitedByAnIdThatDoesNotExistRefusesNothing() {
        // Java keeps the veto. Without it a hallucinated id would stop an answer on the authority
        // of nothing, and the failure would look identical to a real refusal from the outside.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.MODEL, List.of()));
        client.reply = "inv-9: this breaks the stack rule.";

        assertThat(guard.check("use whatever datastore is fastest", rules).breached()).isFalse();
    }

    @Test
    void aRetiredRuleCannotBeCitedEvenThoughItIsStillInTheSet() {
        Invariants rules = Invariants.EMPTY
                .with(rule("inv-1", Check.MODEL, List.of()))
                .with(rule("inv-2", Check.MODEL, List.of()))
                .retire("inv-1", "we hired someone who has run Mongo");
        client.reply = "inv-1: this breaks the stack rule.";

        assertThat(guard.check("use whatever datastore is fastest", rules).breached()).isFalse();
    }

    @Test
    void aRealRuleThatWasNotAmongTheOnesShownRefusesNothing() {
        // The second half of the veto. inv-2's terms are nowhere near this message, so it was never
        // put to the model — a citation of it can only have come from memory of an earlier prompt.
        Invariants rules = Invariants.EMPTY
                .with(rule("inv-1", Check.MODEL, List.of()))
                .with(rule("inv-2", Check.WATCH, List.of("mongo")));
        client.reply = "inv-2: this breaks the stack rule.";

        InvariantGuard.Ruling ruling = guard.check("what should the retry policy be?", rules);

        assertThat(ruling.breached()).isFalse();
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    void aFailedCallLetsTheTurnThroughRatherThanRefusingIt() {
        // Failing closed would turn a provider timeout into "you are not allowed to ask that" —
        // the least explicable error an assistant can produce, and one the user cannot act on.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.MODEL, List.of()));
        client.fail = true;

        assertThat(guard.check("use whatever datastore is fastest", rules).breached()).isFalse();
    }

    @Test
    void aTruncatedReplyIsNotTreatedAsAVerdict() {
        // The ceiling can land mid-id, and half an id either fails to resolve or resolves to a
        // different rule than the one meant. Neither is a thing to refuse somebody on.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.MODEL, List.of()));
        client.reply = "inv-1";
        client.finishReason = "length";

        assertThat(guard.check("use whatever datastore is fastest", rules).breached()).isFalse();
    }

    @Test
    void aForbidHitIsSettledEvenWhenAModelTierRuleIsAlsoInForce() {
        // Cheapest tier first, and it short-circuits. Paying for a call to confirm what a regex has
        // already proved is the one cost the tiering exists to avoid.
        Invariants rules = Invariants.EMPTY
                .with(rule("inv-1", Check.MODEL, List.of()))
                .with(rule("inv-2", Check.FORBID, List.of("mongo")));

        InvariantGuard.Ruling ruling = guard.check("let's use Mongo", rules);

        assertThat(ruling.broken().id()).isEqualTo("inv-2");
        assertThat(client.calls).isZero();
    }

    @Test
    void nothingIsCheckedWhenNoRuleBinds() {
        assertThat(guard.check("let's use Mongo", Invariants.EMPTY).breached()).isFalse();
        assertThat(guard.check("let's use Mongo", null).breached()).isFalse();
        assertThat(guard.check("", Invariants.EMPTY.with(rule("inv-1", Check.MODEL, List.of())))
                .breached()).isFalse();
        assertThat(client.calls).isZero();
    }

    @Test
    void theRefusalHandsBackTheReasonAndTheWayForwardRatherThanJustTheRule() {
        // A refusal that only says no leaves the person with nowhere to go, which is how a rule
        // gets resented and then deleted. The alternative travels with the rule for that reason.
        Invariants rules = Invariants.EMPTY.with(rule("inv-1", Check.FORBID, List.of("mongo")));

        String redirect = guard.check("let's use Mongo", rules).redirect();

        assertThat(redirect)
                .contains("Do not propose a datastore other than Postgres")
                .contains("nobody here has run Mongo in production")
                .contains("materialized view");
    }

    private static final class ScriptedGuard implements LlmClient {

        private ChatRequest last;
        private int calls;
        private boolean fail;
        private String reply = "ok";
        private String finishReason = "stop";

        @Override
        public ChatResponse complete(ChatRequest request) {
            this.last = request;
            this.calls++;
            if (fail) {
                throw new LlmException("guard unavailable");
            }
            return new ChatResponse(reply, request.model(), finishReason, new TokenUsage(30, 6, 36));
        }
    }
}
