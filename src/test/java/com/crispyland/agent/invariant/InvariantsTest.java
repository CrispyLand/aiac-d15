package com.crispyland.agent.invariant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvariantsTest {

    private static Invariant rule(String id, InvariantKind kind, Check check, String text,
                                  List<String> watch) {
        return new Invariant(id, kind, InvariantScope.GLOBAL, check, text, "", "", watch, true, "");
    }

    private static Invariant rule(String id, String text) {
        return rule(id, InvariantKind.STACK, Check.MODEL, text, List.of());
    }

    @Test
    void aRuleDeclaredWithNoIdIsGivenTheNextFreeOne() {
        Invariants held = Invariants.EMPTY
                .with(rule("", "Postgres only."))
                .with(rule("", "No new infrastructure."));

        assertThat(held.all()).extracting(Invariant::id).containsExactly("inv-1", "inv-2");
    }

    @Test
    void amendingARuleReplacesItWhereItStandsRatherThanAppendingAContradiction() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", "Postgres only."))
                .with(rule("inv-2", "No new infrastructure."))
                .with(rule("inv-1", "Postgres only, except for the search index."));

        // Two rules, not three, and the amended one keeps its place. A rule that moves to the end
        // on every edit reads as a new commitment rather than a revision of an old one.
        assertThat(held.all()).extracting(Invariant::id).containsExactly("inv-1", "inv-2");
        assertThat(held.all().getFirst().rule())
                .isEqualTo("Postgres only, except for the search index.");
    }

    @Test
    void aNewIdCountsFromTheHighestEverIssuedSoRetiredOnesAreNotReused() {
        Invariants held = Invariants.EMPTY
                .with(rule("", "one"))
                .with(rule("", "two"))
                .with(rule("", "three"))
                .retire("inv-2", "no longer applies")
                .retire("inv-3", "no longer applies");

        // Counting from size() would hand the new rule "inv-2" — an id already cited in a refusal
        // the user has read. Ids are quoted at people; they must not be recycled.
        assertThat(held.nextId()).isEqualTo("inv-4");
        assertThat(held.with(rule("", "four")).all()).extracting(Invariant::id).contains("inv-4");
    }

    @Test
    void anIdSomebodyTypedByHandKeepsItsNameAndDoesNotFeedTheCounter() {
        Invariants held = Invariants.EMPTY
                .with(rule("no-mongo", "No Mongo."))
                .with(rule("", "No new infrastructure."));

        assertThat(held.all()).extracting(Invariant::id).containsExactly("no-mongo", "inv-1");
    }

    @Test
    void aRuleWithNoTextIsNotDeclaredAtAll() {
        Invariants held = Invariants.EMPTY.with(rule("inv-1", "  ")).with(null);

        assertThat(held).isEqualTo(Invariants.EMPTY);
    }

    @Test
    void citingAnUnknownOrRetiredRuleYieldsNothingToRefuseOn() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", "Postgres only."))
                .with(rule("inv-2", "No new infrastructure."))
                .retire("inv-2", "we bought a cache");

        // This is the check that keeps the hybrid honest: the model proposes which rule was
        // broken, Java decides whether that rule exists and still binds. A hallucinated INV-9
        // would otherwise refuse an answer on the authority of nothing at all.
        assertThat(held.cited("INV-1")).isNotNull();
        assertThat(held.cited(" inv-1 ")).isNotNull();
        assertThat(held.cited("inv-9")).isNull();
        assertThat(held.cited("inv-2")).isNull();
        assertThat(held.cited("")).isNull();
        assertThat(held.cited(null)).isNull();
    }

    @Test
    void onlyRulesWithTermsToMatchAreCheckedInJava() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", InvariantKind.STACK, Check.FORBID, "No Mongo.", List.of("mongo")))
                .with(rule("inv-2", InvariantKind.STACK, Check.WATCH, "Think before caching.",
                        List.of("redis")))
                // A MODEL rule has nothing literal to give it away, so the Java pass must skip it
                // rather than silently never firing on terms it happens to carry.
                .with(rule("inv-3", InvariantKind.BUSINESS, Check.MODEL, "Never quote below cost.",
                        List.of("mongo")));

        List<Invariants.Hit> hits = held.hits("put mongo behind redis");

        assertThat(hits).extracting(hit -> hit.invariant().id()).containsExactly("inv-1", "inv-2");
        assertThat(hits.getFirst().terms()).containsExactly("mongo");
        assertThat(hits.getFirst().decidesAlone()).isTrue();
        assertThat(hits.get(1).decidesAlone()).isFalse();
    }

    @Test
    void aRetiredRuleStopsMatchingButIsStillOnTheRecord() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", InvariantKind.STACK, Check.FORBID, "No Mongo.", List.of("mongo")))
                .retire("inv-1", "the client already runs it");

        assertThat(held.hits("we could use mongo")).isEmpty();
        assertThat(held.isPresent()).isFalse();
        assertThat(held.binding()).isEmpty();
        // Kept, so the page can show that a constraint was lifted, and why.
        assertThat(held.retired()).extracting(Invariant::retiredWhy)
                .containsExactly("the client already runs it");
        assertThat(held.restore("inv-1").isPresent()).isTrue();
    }

    @Test
    void retiringAnUnknownIdChangesNothingAndIsNotAnError() {
        Invariants held = Invariants.EMPTY.with(rule("inv-1", "Postgres only."));

        assertThat(held.retire("inv-9", "typo")).isSameAs(held);
        assertThat(held.restore("inv-9")).isSameAs(held);
        assertThat(held.retire(null, "typo")).isSameAs(held);
    }

    @Test
    void everyChangeBumpsTheRevisionAndANonChangeDoesNot() {
        Invariants held = Invariants.EMPTY.with(rule("inv-1", "Postgres only."));
        assertThat(held.revision()).isEqualTo(1);
        assertThat(held.retire("inv-1", "done").revision()).isEqualTo(2);
        // Retiring what is already retired is not a change, so it does not count as one.
        assertThat(held.retire("inv-1", "done").retire("inv-1", "done").revision()).isEqualTo(2);
    }

    @Test
    void theBlockIsGroupedByKindAndCarriesOnlyWhatStillBinds() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", InvariantKind.STACK, Check.MODEL, "Postgres only.", List.of()))
                .with(rule("inv-2", InvariantKind.BUSINESS, Check.MODEL,
                        "Never quote below cost.", List.of()))
                .with(rule("inv-3", InvariantKind.STACK, Check.MODEL, "No Mongo.", List.of()))
                .retire("inv-3", "the client already runs it");

        String block = held.render();

        // The heading is what tells the model how much room there is to negotiate — a stack
        // constraint and a business rule read as equally rigid in a flat list, and they are not.
        assertThat(block)
                .contains(InvariantKind.STACK.label())
                .contains(InvariantKind.BUSINESS.label())
                .contains("[INV-1]")
                .contains("[INV-2]")
                // A retired rule costs nothing at the model, which is the point of excluding it.
                .doesNotContain("[INV-3]");
        assertThat(block.indexOf("[INV-1]")).isLessThan(block.indexOf("[INV-2]"));
        assertThat(Invariants.EMPTY.render()).isEmpty();
    }

    @Test
    void rulesCanBeAskedForByKindAndByScope() {
        Invariants held = Invariants.EMPTY
                .with(rule("inv-1", InvariantKind.STACK, Check.MODEL, "Postgres only.", List.of()))
                .with(new Invariant("inv-2", InvariantKind.STACK, InvariantScope.TASK, Check.MODEL,
                        "Do not touch the billing module.", "", "", List.of(), true, ""));

        assertThat(held.of(InvariantKind.STACK)).hasSize(2);
        assertThat(held.of(InvariantKind.BUSINESS)).isEmpty();
        assertThat(held.inScope(InvariantScope.GLOBAL)).extracting(Invariant::id)
                .containsExactly("inv-1");
        assertThat(held.inScope(InvariantScope.TASK)).extracting(Invariant::id)
                .containsExactly("inv-2");
        assertThat(held.alwaysChecked()).hasSize(2);
    }
}
