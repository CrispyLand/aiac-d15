package com.crispyland.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Restarting the JVM is simulated by building a second store over the same file. */
class JsonFileBranchStoreTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("nested").resolve("branches.json");
    }

    private JsonFileBranchStore store() {
        return new JsonFileBranchStore(JsonMapper.builder().build(), file);
    }

    @Test
    void branchesOutliveTheProcessThatForkedThem() {
        // The transcripts already survive a restart. Refs that did not would silently merge two
        // lines of a conversation back into one on the next boot.
        store().add("c1", new Branch("option-b", "option b", Branch.TRUNK, 4));

        assertThat(store().all("c1")).extracting(Branch::id, Branch::parentId, Branch::forkedAt)
                .containsExactly(tuple("main", null, 0), tuple("option-b", "main", 4));
    }

    @Test
    void theOpenBranchIsRememberedAndNotJustTheListOfThem() {
        JsonFileBranchStore first = store();
        first.add("c1", new Branch("option-b", "option b", Branch.TRUNK, 4));
        first.activate("c1", Branch.TRUNK);

        assertThat(store().active("c1")).isEqualTo(Branch.TRUNK);
    }

    @Test
    void aConversationThatNeverForkedIsNotWrittenDownAtAll() {
        store().activate("c1", Branch.TRUNK);

        assertThat(store().all("c1")).containsExactly(Branch.MAIN);
    }

    @Test
    void aCorruptFileCostsTheBranchesButNeverTheBoot() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not json");

        assertThatCode(() -> assertThat(store().all("c1")).containsExactly(Branch.MAIN))
                .doesNotThrowAnyException();
    }
}
