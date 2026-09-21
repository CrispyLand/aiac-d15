package com.crispyland.agent.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvariantTest {

    private static Invariant rule(Check check, List<String> watch) {
        return new Invariant("inv-1", InvariantKind.STACK, InvariantScope.GLOBAL, check,
                "Do not propose a datastore other than Postgres.",
                "one ops surface, and nobody on the team has run Mongo in production",
                "use Postgres — an unlogged table or a materialized view for caching",
                watch, true, "");
    }

    @Test
    void aWatchedTermMatchesAsAWholeWordAndNotAsASubstring() {
        // The bug this exists to stop: "redis" lives inside "rediscover", so a plain contains()
        // would refuse a sentence about rediscovering requirements on the authority of a stack rule.
        Invariant invariant = rule(Check.WATCH, List.of("redis"));

        assertThat(invariant.matches("we could add Redis in front of it")).containsExactly("redis");
        assertThat(invariant.matches("let's rediscover what the requirements were")).isEmpty();
        assertThat(invariant.matches("redistribute the load")).isEmpty();
    }

    @Test
    void matchingIgnoresCaseAndPunctuationAroundTheTerm() {
        Invariant invariant = rule(Check.WATCH, List.of("MongoDB"));

        assertThat(invariant.matches("Use MONGODB.")).containsExactly("MongoDB");
        assertThat(invariant.matches("(mongodb) is an option")).containsExactly("MongoDB");
        assertThat(invariant.matches("mongodbatlas")).isEmpty();
    }

    @Test
    void aTermContainingRegexCharactersIsMatchedLiterally() {
        // Terms are user-written, so "node.js" and "c++" arrive as-is. Unquoted, the dot in
        // "node.js" would match "nodexjs" and the plusses in "c++" would not compile at all.
        Invariant invariant = rule(Check.WATCH, List.of("node.js", "c++"));

        assertThat(invariant.matches("rewrite it in node.js")).containsExactly("node.js");
        assertThat(invariant.matches("nodexjs is not a thing")).isEmpty();
        assertThat(invariant.matches("drop into c++ for the hot loop")).containsExactly("c++");
    }

    @Test
    void aTermInCyrillicMatchesOnLetterBoundariesToo() {
        // The mentor's own example is a Russian-language rule, and \w would treat every Cyrillic
        // letter as a word boundary — making the term match inside any longer word.
        Invariant invariant = rule(Check.FORBID, List.of("фронтенд"));

        assertThat(invariant.matches("перепишем фронтенд на Vue")).containsExactly("фронтенд");
        assertThat(invariant.matches("фронтендер напишет")).isEmpty();
    }

    @Test
    void aDeterministicCheckWithNothingToWatchForFallsBackToAskingTheModel() {
        // A FORBID rule with no terms can never fire, which would leave it enforced by nothing at
        // all. Expensive beats absent, and the fallback is visible in the check mode rather than
        // hidden in the guard.
        assertThat(rule(Check.FORBID, List.of()).check()).isEqualTo(Check.MODEL);
        assertThat(rule(Check.WATCH, List.of()).check()).isEqualTo(Check.MODEL);
        assertThat(rule(Check.FORBID, List.of("redis")).check()).isEqualTo(Check.FORBID);
    }

    @Test
    void watchedTermsAreDeduplicatedCaseInsensitivelyAndKeepTheirOrder() {
        // Arrays.asList, not List.of — a null term is exactly what this test is about, and
        // List.of refuses one before the record ever gets to handle it.
        Invariant invariant = rule(Check.WATCH, Arrays.asList("Redis", "  ", "redis", "Mongo", null));

        assertThat(invariant.watch()).containsExactly("Redis", "Mongo");
    }

    @Test
    void retiringKeepsTheRuleAndTheReasonItStoppedBinding() {
        Invariant retired = rule(Check.WATCH, List.of("redis")).retire("moved to a managed cache");

        assertThat(retired.active()).isFalse();
        assertThat(retired.binds()).isFalse();
        assertThat(retired.rule()).isEqualTo("Do not propose a datastore other than Postgres.");
        assertThat(retired.retiredWhy()).isEqualTo("moved to a managed cache");
        // Restoring drops the reason, because it is no longer true of the rule.
        assertThat(retired.restore().retiredWhy()).isEmpty();
        assertThat(retired.restore().binds()).isTrue();
    }

    @Test
    void theRefusalNamesTheRuleTheReasonAndTheWayForwardInOneSentence() {
        // This is the day's second verification question — how the refusal explains itself. All
        // three parts come from stored fields, so no call is needed to produce it.
        String redirect = rule(Check.FORBID, List.of("mongo")).redirect();

        assertThat(redirect)
                .contains("Do not propose a datastore other than Postgres")
                .contains("because one ops surface")
                .contains("(INV-1)")
                .contains("What I can do instead: use Postgres")
                // The rule's own full stop must not survive into the middle of the sentence.
                .doesNotContain("Postgres., because");
    }

    @Test
    void aRuleWithNoRationaleStillExplainsItselfWithoutStrayPunctuation() {
        Invariant bare = new Invariant("inv-2", InvariantKind.BUSINESS, InvariantScope.TASK,
                Check.MODEL, "Never quote a price below cost.", "", "", List.of(), true, "");

        assertThat(bare.redirect())
                .isEqualTo("I can't take this direction: Never quote a price below cost (INV-2).");
    }

    @Test
    void theBlockSentToTheModelCarriesTheAlternativeSoItCanComplyFirstTimeRound() {
        // Told only what it may not do, a model refuses and stops. Told what to do instead, it can
        // honour the rule in the first answer — which is a whole guard call cheaper.
        assertThat(rule(Check.WATCH, List.of("redis")).render())
                .contains("[INV-1]")
                .contains("why: one ops surface")
                .contains("instead: use Postgres");
    }

    @Test
    void anIdIsLowercasedAndHyphenatedSoOneRuleCannotBeCitedUnderTwoSpellings() {
        Invariant invariant = new Invariant("  INV 7 ", InvariantKind.STACK, null, Check.MODEL,
                "No new infrastructure.", "", "", List.of(), true, "");

        assertThat(invariant.id()).isEqualTo("inv-7");
        assertThat(invariant.label()).isEqualTo("INV-7");
        assertThat(invariant.scope()).isEqualTo(InvariantScope.GLOBAL);
    }

    @Test
    void aRuleWithNoTextIsNotEnforceableAndSaysSo() {
        Invariant empty = new Invariant("inv-3", InvariantKind.STACK, InvariantScope.GLOBAL,
                Check.MODEL, "   ", "", "", List.of(), true, "");

        assertThat(empty.isPresent()).isFalse();
        assertThat(empty.binds()).isFalse();
    }
}
