package com.crispyland.agent.invariant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Restarting the JVM is simulated by building a second store over the same file. */
class JsonFileInvariantStoreTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("nested").resolve("invariants.json");
    }

    private JsonFileInvariantStore store() {
        return new JsonFileInvariantStore(JsonMapper.builder().build(), file);
    }

    private static Invariant rule(String id, String text) {
        return new Invariant(id, InvariantKind.STACK, InvariantScope.GLOBAL, Check.FORBID, text,
                "one ops surface", "use Postgres", List.of("mongo"), true, "");
    }

    @Test
    void aConstraintOutlivesTheProcessThatWasToldAboutIt() {
        store().declare("nur", rule("", "Do not propose a datastore other than Postgres."));

        Invariant restored = store().held("nur").all().getFirst();

        assertThat(restored.id()).isEqualTo("inv-1");
        assertThat(restored.rule()).isEqualTo("Do not propose a datastore other than Postgres.");
        assertThat(restored.why()).isEqualTo("one ops surface");
        assertThat(restored.instead()).isEqualTo("use Postgres");
        assertThat(restored.check()).isEqualTo(Check.FORBID);
        assertThat(restored.watch()).containsExactly("mongo");
        assertThat(restored.binds()).isTrue();
    }

    @Test
    void aRuleThatWasLiftedStaysLiftedAcrossARestart() {
        JsonFileInvariantStore store = store();
        store.declare("nur", rule("inv-1", "No Mongo."));
        store.retire("nur", "inv-1", "the client already runs it");

        Invariants restored = store().held("nur");

        // The dangerous direction is the other one: a retired rule that comes back in force after
        // a restart refuses things the user has already said are fine, and nothing on the page
        // would explain why.
        assertThat(restored.isPresent()).isFalse();
        assertThat(restored.retired()).extracting(Invariant::retiredWhy)
                .containsExactly("the client already runs it");
        assertThat(store().restore("nur", "inv-1").isPresent()).isTrue();
        assertThat(store().held("nur").isPresent()).isTrue();
    }

    @Test
    void visitorsCannotReadEachOthersConstraints() {
        JsonFileInvariantStore store = store();
        store.declare("alice", rule("", "No Mongo."));
        store.declare("bob", rule("", "No new infrastructure."));

        JsonFileInvariantStore restarted = store();
        assertThat(restarted.held("alice").all()).extracting(Invariant::rule)
                .containsExactly("No Mongo.");
        assertThat(restarted.held("bob").all()).extracting(Invariant::rule)
                .containsExactly("No new infrastructure.");
    }

    @Test
    void aVisitorNobodyHasConstrainedIsFreeRatherThanNull() {
        assertThat(store().held("stranger")).isEqualTo(Invariants.EMPTY);
        assertThat(file).doesNotExist();
    }

    @Test
    void forgettingAVisitorTakesTheirRulesWithThem() {
        JsonFileInvariantStore store = store();
        store.declare("visitor-uuid", rule("", "No Mongo."));
        assertThat(contents()).contains("visitor-uuid");

        store.clear("visitor-uuid");

        assertThat(contents()).doesNotContain("visitor-uuid");
        assertThat(store().held("visitor-uuid").isPresent()).isFalse();
    }

    @Test
    void aRuleStoredUnderAKindThisBuildNoLongerHasIsKeptRatherThanDropped() throws IOException {
        write("""
                {"visitors":{"nur":{"revision":2,"invariants":[
                  {"id":"inv-1","kind":"telepathy","scope":"global","check":"semaphore",
                   "rule":"No Mongo.","why":"one ops surface","instead":"use Postgres",
                   "active":true,"retiredWhy":"","watch":["mongo"]}]}}}""");

        Invariant kept = store().held("nur").all().getFirst();

        // This is the deliberate divergence from long-term memory, which drops what it cannot
        // classify. Dropping a remembered fact loses a detail; dropping an invariant silently
        // removes a constraint the user still believes is in force, and they find out when the
        // agent proposes the very thing they forbade.
        assertThat(kept.rule()).isEqualTo("No Mongo.");
        assertThat(kept.kind()).isEqualTo(InvariantKind.TECHNICAL);
        assertThat(kept.check()).isEqualTo(Check.MODEL);
        assertThat(kept.binds()).isTrue();
    }

    @Test
    void aRuleStoredWithoutAnActiveFlagIsAssumedToStillBind() throws IOException {
        write("""
                {"visitors":{"nur":{"revision":1,"invariants":[
                  {"id":"inv-1","kind":"stack","scope":"global","check":"model",
                   "rule":"No Mongo."}]}}}""");

        assertThat(store().held("nur").all().getFirst().binds()).isTrue();
    }

    @Test
    void aCorruptFileDoesNotStopTheAppBooting() throws IOException {
        write("{{{ not json");

        assertThatCode(() -> assertThat(store().held("nur").isPresent()).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void declarationOrderAndRevisionSurviveTheRestart() {
        JsonFileInvariantStore store = store();
        store.declare("nur", rule("", "No Mongo."));
        store.declare("nur", rule("", "No new infrastructure."));
        store.declare("nur", rule("inv-1", "No Mongo, and no Cassandra."));

        assertThat(store().held("nur").all())
                .extracting(Invariant::id, Invariant::rule)
                .containsExactly(
                        tuple("inv-1", "No Mongo, and no Cassandra."),
                        tuple("inv-2", "No new infrastructure."));
        assertThat(store().held("nur").revision()).isEqualTo(3);
        // The next rule must not be handed an id already cited in a refusal the user has read.
        assertThat(store().held("nur").nextId()).isEqualTo("inv-3");
    }

    @Test
    void writesLeaveNoTemporaryFileBehind() {
        store().declare("nur", rule("", "No Mongo."));

        assertThat(file).exists();
        assertThat(file.resolveSibling("invariants.json.tmp")).doesNotExist();
    }

    private void write(String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private String contents() {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file, e);
        }
    }
}
