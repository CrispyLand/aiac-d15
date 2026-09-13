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
class JsonFileConversationStoreTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("nested").resolve("conversations.json");
    }

    private JsonFileConversationStore store(int maxMessages) {
        return new JsonFileConversationStore(JsonMapper.builder().build(), file, maxMessages);
    }

    @Test
    void aDialogueSurvivesTheProcessThatWroteIt() {
        store(20).append("c1", List.of(Message.user("my name is Nur"), Message.assistant("Hello, Nur.")));

        // Fresh instance, same file — this is the restart.
        assertThat(store(20).history("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "my name is Nur"), tuple("assistant", "Hello, Nur."));
    }

    @Test
    void conversationsStayIsolatedAcrossARestart() {
        JsonFileConversationStore first = store(20);
        first.append("alice", List.of(Message.user("hello from alice")));
        first.append("bob", List.of(Message.user("hello from bob")));

        JsonFileConversationStore restarted = store(20);
        assertThat(restarted.history("alice")).extracting(Message::content).containsExactly("hello from alice");
        assertThat(restarted.history("bob")).extracting(Message::content).containsExactly("hello from bob");
    }

    @Test
    void perMessageStatsRoundTripSoTheRestoredTranscriptStillRenders() {
        store(20).append("c1", List.of(
                Message.user("hello").withStats(MessageStats.forPrompt(10, "openai/gpt-oss-20b")),
                Message.assistant("hi").withStats(
                        MessageStats.forCompletion(5, 15, 380, "openai/gpt-oss-20b", "stop"))));

        List<Message> restored = store(20).history("c1");
        assertThat(restored.get(0).stats().promptTokens()).isEqualTo(10);
        assertThat(restored.get(1).stats()).isEqualTo(
                new MessageStats(0, 5, 15, 380, "openai/gpt-oss-20b", "stop"));
    }

    @Test
    void theWindowIsAppliedBeforeAnythingIsWritten() {
        JsonFileConversationStore windowed = store(2);
        windowed.append("c1", List.of(Message.user("first"), Message.assistant("reply 1")));
        windowed.append("c1", List.of(Message.user("second"), Message.assistant("reply 2")));

        // The trimmed stack is what persists — dropped turns are not hiding in the file.
        assertThat(store(2).history("c1"))
                .extracting(Message::role, Message::content)
                .containsExactly(tuple("user", "second"), tuple("assistant", "reply 2"));
    }

    @Test
    void aCompactedConversationRestoresWithItsSummaryInsteadOfItsMessages() {
        JsonFileConversationStore first = store(20);
        first.append("c1", List.of(Message.user("my name is Nur"), Message.assistant("Hello, Nur."),
                Message.user("still here"), Message.assistant("Yes.")));
        first.compact("c1", Summary.EMPTY.rewrittenAs("user is called Nur", 2, 40, 60), 2);

        JsonFileConversationStore restarted = store(20);
        // The folded messages are gone from disk; the notes that replaced them are not.
        assertThat(restarted.history("c1")).extracting(Message::content)
                .containsExactly("still here", "Yes.");
        assertThat(restarted.summary("c1"))
                .isEqualTo(new Summary("user is called Nur", 1, 2, 40, 60));
    }

    @Test
    void anUncompressedConversationRestoresWithNoSummary() {
        store(20).append("c1", List.of(Message.user("hello")));

        assertThat(store(20).summary("c1")).isEqualTo(Summary.EMPTY);
    }

    @Test
    void clearingRemovesTheConversationFromDisk() {
        JsonFileConversationStore first = store(20);
        first.append("c1", List.of(Message.user("hello")));
        first.clear("c1");

        assertThat(store(20).history("c1")).isEmpty();
    }

    @Test
    void aCorruptFileStartsEmptyInsteadOfBrickingStartup() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{{{ not json");

        assertThatCode(() -> assertThat(store(20).history("c1")).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    void aMissingFileIsSimplyAnEmptyDialogue() {
        assertThat(store(20).history("c1")).isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    void writesLeaveNoTemporaryFileBehind() {
        store(20).append("c1", List.of(Message.user("hello")));

        assertThat(file).exists();
        assertThat(file.resolveSibling("conversations.json.tmp")).doesNotExist();
    }
}
