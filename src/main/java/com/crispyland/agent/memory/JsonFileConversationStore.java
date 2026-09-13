package com.crispyland.agent.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the message stack on disk so a restart does not erase the dialogue. The file is read
 * once at construction and written through on every mutation; reads are served from memory.
 * <p>
 * The on-disk shape is mapped by hand, exactly as {@code GroqLlmClient} maps the wire format:
 * {@link Message} and {@link MessageStats} stay pure domain records with no serialization
 * annotations, and this class owns the translation.
 */
public class JsonFileConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileConversationStore.class);

    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Summary> summaries = new ConcurrentHashMap<>();
    private final Map<String, Facts> facts = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path file;
    private final int maxMessages;

    /**
     * @param file        where the dialogue is persisted; missing or unreadable means "start empty"
     * @param maxMessages rolling window size; {@code <= 0} keeps everything
     */
    public JsonFileConversationStore(ObjectMapper mapper, Path file, int maxMessages) {
        this.mapper = mapper;
        this.file = file;
        this.maxMessages = maxMessages;
        load();
    }

    @Override
    public List<Message> history(String conversationId) {
        return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        conversations.compute(conversationId,
                (key, existing) -> Conversations.appendTrimmed(existing, messages, maxMessages));
        flush();
    }

    @Override
    public Summary summary(String conversationId) {
        return summaries.getOrDefault(conversationId, Summary.EMPTY);
    }

    @Override
    public void compact(String conversationId, Summary summary, int foldedMessages) {
        conversations.computeIfPresent(conversationId,
                (key, existing) -> Conversations.drop(existing, foldedMessages));
        summaries.put(conversationId, summary);
        flush();
    }

    @Override
    public Facts facts(String conversationId) {
        return facts.getOrDefault(conversationId, Facts.EMPTY);
    }

    @Override
    public void saveFacts(String conversationId, Facts updated) {
        facts.put(conversationId, updated);
        flush();
    }

    @Override
    public int copy(String fromConversationId, String toConversationId, int messages) {
        List<Message> source = history(fromConversationId);
        List<Message> copied = Conversations.head(source, messages);
        conversations.put(toConversationId, copied);
        summaries.put(toConversationId, summary(fromConversationId));
        facts.put(toConversationId, (copied.size() == source.size())
                ? facts(fromConversationId) : Facts.EMPTY);
        flush();
        return copied.size();
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
        summaries.remove(conversationId);
        facts.remove(conversationId);
        flush();
    }

    /**
     * Restores the dialogue recorded by a previous run. A corrupt or unreadable file must never
     * stop the application from booting — the agent simply starts with no memory and the next
     * turn overwrites the bad file.
     */
    private void load() {
        if (!Files.isRegularFile(file)) {
            log.info("No conversation history at {} — starting with an empty dialogue.", file.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            JsonNode stored = root.path("conversations");
            stored.propertyNames().forEach(id -> {
                JsonNode entry = stored.path(id);
                conversations.put(id, readMessages(entry.path("messages")));
                JsonNode summary = entry.path("summary");
                if (summary.isObject()) {
                    summaries.put(id, readSummary(summary));
                }
                JsonNode stickyFacts = entry.path("facts");
                if (stickyFacts.isObject()) {
                    facts.put(id, readFacts(stickyFacts));
                }
            });
            log.info("Restored {} conversation(s) from {}", conversations.size(), file.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read conversation history from {} ({}) — starting with an empty dialogue.",
                    file.toAbsolutePath(), e.getMessage());
            conversations.clear();
        }
    }

    /**
     * Writes via a temporary file and an atomic rename, so a crash mid-write leaves the previous
     * good history in place rather than a half-written one.
     */
    private synchronized void flush() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()));
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist conversation history to " + file.toAbsolutePath(), e);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode stored = root.putObject("conversations");
        conversations.forEach((id, messages) -> {
            ObjectNode entry = stored.putObject(id);
            Summary summary = summaries.get(id);
            if (summary != null && summary.isPresent()) {
                writeSummary(entry.putObject("summary"), summary);
            }
            Facts stickyFacts = facts.get(id);
            if (stickyFacts != null && stickyFacts.isPresent()) {
                writeFacts(entry.putObject("facts"), stickyFacts);
            }
            ArrayNode array = entry.putArray("messages");
            for (Message message : messages) {
                ObjectNode node = array.addObject();
                node.put("role", message.role());
                node.put("content", message.content());
                if (message.stats() != null) {
                    writeStats(node.putObject("stats"), message.stats());
                }
            }
        });
        return root;
    }

    private static void writeSummary(ObjectNode node, Summary summary) {
        node.put("text", summary.text());
        node.put("revision", summary.revision());
        node.put("coveredMessages", summary.coveredMessages());
        node.put("replacedTokens", summary.replacedTokens());
        node.put("buildTokens", summary.buildTokens());
    }

    private static Summary readSummary(JsonNode node) {
        return new Summary(
                text(node.path("text")),
                (int) number(node.path("revision")),
                (int) number(node.path("coveredMessages")),
                number(node.path("replacedTokens")),
                number(node.path("buildTokens")));
    }

    /** An array, not an object: the order facts were learned in is part of how the block reads. */
    private static void writeFacts(ObjectNode node, Facts facts) {
        node.put("revision", facts.revision());
        node.put("buildTokens", facts.buildTokens());
        ArrayNode array = node.putArray("entries");
        for (Facts.Fact fact : facts.entries()) {
            ObjectNode entry = array.addObject();
            entry.put("key", fact.key());
            entry.put("value", fact.value());
        }
    }

    private static Facts readFacts(JsonNode node) {
        List<Facts.Fact> entries = new ArrayList<>();
        for (JsonNode entry : node.path("entries")) {
            entries.add(new Facts.Fact(text(entry.path("key")), text(entry.path("value"))));
        }
        return new Facts(entries, (int) number(node.path("revision")), number(node.path("buildTokens")));
    }

    private static void writeStats(ObjectNode node, MessageStats stats) {
        node.put("promptTokens", stats.promptTokens());
        node.put("completionTokens", stats.completionTokens());
        node.put("totalTokens", stats.totalTokens());
        node.put("latencyMillis", stats.latencyMillis());
        node.put("model", stats.model());
        node.put("finishReason", stats.finishReason());
    }

    private static List<Message> readMessages(JsonNode array) {
        List<Message> messages = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            Message message = new Message(text(node.path("role")), text(node.path("content")), null);
            JsonNode stats = node.path("stats");
            messages.add(stats.isObject() ? message.withStats(readStats(stats)) : message);
        }
        return messages;
    }

    private static MessageStats readStats(JsonNode node) {
        return new MessageStats(
                number(node.path("promptTokens")),
                number(node.path("completionTokens")),
                number(node.path("totalTokens")),
                number(node.path("latencyMillis")),
                text(node.path("model")),
                text(node.path("finishReason")));
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }

    private static long number(JsonNode node) {
        return node.isNumber() ? node.longValue() : 0L;
    }
}
