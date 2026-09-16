package com.crispyland.agent.memory;

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
class JsonFileLongTermStoreTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("nested").resolve("memory.json");
    }

    private JsonFileLongTermStore store(int maxEntries) {
        return new JsonFileLongTermStore(JsonMapper.builder().build(), file, maxEntries);
    }

    private static LongTermMemory.Entry entry(LongTermKind kind, String key, String value) {
        return new LongTermMemory.Entry(kind, key, value);
    }

    @Test
    void whatIsKnownAboutAVisitorSurvivesTheProcessThatLearnedIt() {
        store(24).remember("nur", List.of(
                entry(LongTermKind.PROFILE, "name", "Nur"),
                entry(LongTermKind.KNOWLEDGE, "project", "meetupper")));

        // Fresh instance, same file — this is the restart.
        assertThat(store(24).recall("nur").entries())
                .extracting(LongTermMemory.Entry::kind, LongTermMemory.Entry::key,
                        LongTermMemory.Entry::value)
                .containsExactly(
                        tuple(LongTermKind.PROFILE, "name", "Nur"),
                        tuple(LongTermKind.KNOWLEDGE, "project", "meetupper"));
    }

    @Test
    void visitorsCannotReadEachOthersMemory() {
        JsonFileLongTermStore first = store(24);
        first.remember("alice", List.of(entry(LongTermKind.PROFILE, "name", "Alice")));
        first.remember("bob", List.of(entry(LongTermKind.PROFILE, "name", "Bob")));

        JsonFileLongTermStore restarted = store(24);
        assertThat(restarted.recall("alice").entries())
                .extracting(LongTermMemory.Entry::value).containsExactly("Alice");
        assertThat(restarted.recall("bob").entries())
                .extracting(LongTermMemory.Entry::value).containsExactly("Bob");
    }

    @Test
    void aChangedMindOverwritesInPlaceRatherThanArguingWithItself() {
        JsonFileLongTermStore store = store(24);
        store.remember("nur", List.of(entry(LongTermKind.DECISION, "store", "Postgres 15")));
        store.remember("nur", List.of(entry(LongTermKind.DECISION, "store", "Postgres 16")));

        // One entry, not two contradictory ones. This is the whole reason the block is keyed:
        // appended memory can only ever contradict itself, and eventually does.
        assertThat(store(24).recall("nur").entries())
                .extracting(LongTermMemory.Entry::value).containsExactly("Postgres 16");
    }

    @Test
    void theCapRefusesNewSubjectsButNeverACorrection() {
        JsonFileLongTermStore store = store(2);
        store.remember("nur", List.of(
                entry(LongTermKind.PROFILE, "name", "Nur"),
                entry(LongTermKind.KNOWLEDGE, "project", "meetupper"),
                entry(LongTermKind.KNOWLEDGE, "language", "Java")));
        store.remember("nur", List.of(entry(LongTermKind.PROFILE, "name", "Nurzhan")));

        assertThat(store.recall("nur").entries())
                .extracting(LongTermMemory.Entry::key, LongTermMemory.Entry::value)
                .containsExactly(tuple("name", "Nurzhan"), tuple("project", "meetupper"));
    }

    @Test
    void forgettingOneEntryRemovesItFromDiskAndLeavesTheRest() {
        JsonFileLongTermStore store = store(24);
        store.remember("nur", List.of(
                entry(LongTermKind.PROFILE, "name", "Nur"),
                entry(LongTermKind.KNOWLEDGE, "project", "meetupper")));

        store.forget("nur", "profile:name");

        assertThat(store(24).recall("nur").entries())
                .extracting(LongTermMemory.Entry::key).containsExactly("project");
        assertThat(contents()).doesNotContain("Nur").contains("meetupper");
    }

    @Test
    void forgetMeLeavesNothingOnDiskThatStillNamesThem() {
        JsonFileLongTermStore store = store(24);
        store.remember("visitor-uuid", List.of(
                entry(LongTermKind.PROFILE, "name", "Nur"),
                entry(LongTermKind.PROFILE, "email", "nur@example.com")));
        assertThat(contents()).contains("visitor-uuid").contains("nur@example.com");

        store.forgetAll("visitor-uuid");

        // The key goes too, not just the entries under it. An emptied-but-present record is
        // still a record, and the visitor asked to be gone — not to be blank.
        assertThat(contents()).doesNotContain("visitor-uuid").doesNotContain("nur@example.com");
        assertThat(store(24).recall("visitor-uuid").isPresent()).isFalse();
    }

    @Test
    void forgetMeIsOnDiskBeforeItReturns() {
        JsonFileLongTermStore store = store(24);
        store.remember("visitor-uuid", List.of(entry(LongTermKind.PROFILE, "name", "Nur")));

        store.forgetAll("visitor-uuid");

        // The caller rotates the cookie next, after which nothing can address this record again.
        // A delete still sitting in memory when the process dies is a record nobody can reach
        // and nobody can remove — which is worse than never having offered the button.
        assertThat(store(24).recall("visitor-uuid").isPresent()).isFalse();
    }

    @Test
    void anEntryStoredUnderAKindThisBuildNoLongerHasIsDroppedRatherThanGuessedAt() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"visitors":{"nur":{"revision":1,"entries":[
                  {"kind":"telepathy","key":"mood","value":"cheerful"},
                  {"kind":"profile","key":"name","value":"Nur"}]}}}""");

        // Refusing to store something is recoverable; storing it under the wrong lifetime is not.
        assertThat(store(24).recall("nur").entries())
                .extracting(LongTermMemory.Entry::key).containsExactly("name");
    }

    @Test
    void aCorruptFileMeetsEveryoneAsAStrangerInsteadOfBrickingStartup() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{{{ not json");

        assertThatCode(() -> assertThat(store(24).recall("nur").isPresent()).isFalse())
                .doesNotThrowAnyException();
    }

    @Test
    void aMissingFileIsSimplyANewVisitor() {
        assertThat(store(24).recall("nur").isPresent()).isFalse();
        assertThat(file).doesNotExist();
    }

    @Test
    void writesLeaveNoTemporaryFileBehind() {
        store(24).remember("nur", List.of(entry(LongTermKind.PROFILE, "name", "Nur")));

        assertThat(file).exists();
        assertThat(file.resolveSibling("memory.json.tmp")).doesNotExist();
    }

    private String contents() {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file, e);
        }
    }
}
