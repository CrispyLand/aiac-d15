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
 * Long-term memory on disk, in its own file — never inside the conversation file.
 * <p>
 * Separate files because the layers have separate lifetimes and a shared file quietly ties them
 * together: clearing a conversation rewrites the conversation file, and anything sharing it is one
 * bug away from being cleared with it. It also makes the claim inspectable. "These three things
 * are stored separately" is something you can check with {@code ls}, not something you have to
 * take on trust from a comment.
 * <p>
 * The on-disk shape is mapped by hand, like every other store here: {@link LongTermMemory} stays a
 * pure domain record with no serialization annotations.
 */
public class JsonFileLongTermStore implements LongTermStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileLongTermStore.class);

    private final Map<String, LongTermMemory> visitors = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path file;
    private final int maxEntries;

    /**
     * @param file       where long-term memory is persisted; missing or unreadable means "nobody
     *                   has been here before"
     * @param maxEntries ceiling per visitor; {@code <= 0} for none
     */
    public JsonFileLongTermStore(ObjectMapper mapper, Path file, int maxEntries) {
        this.mapper = mapper;
        this.file = file;
        this.maxEntries = maxEntries;
        load();
    }

    @Override
    public LongTermMemory recall(String visitorId) {
        return visitors.getOrDefault(visitorId, LongTermMemory.EMPTY);
    }

    @Override
    public LongTermMemory remember(String visitorId, List<LongTermMemory.Entry> entries) {
        LongTermMemory updated = visitors.compute(visitorId, (key, held) ->
                (held == null ? LongTermMemory.EMPTY : held).updatedWith(entries, maxEntries));
        flush();
        return updated;
    }

    @Override
    public LongTermMemory forget(String visitorId, String entryId) {
        LongTermMemory updated = visitors.compute(visitorId, (key, held) ->
                (held == null ? LongTermMemory.EMPTY : held).without(entryId));
        flush();
        return updated;
    }

    /**
     * Removes the visitor's key as well as their entries, and writes before returning.
     * <p>
     * Writing synchronously matters here in a way it does not for the other mutations: the caller
     * is about to rotate the visitor's cookie, after which nothing can address this record again.
     * A forget that is still only in memory when the process dies is a record nobody can reach and
     * nobody can delete.
     */
    @Override
    public void forgetAll(String visitorId) {
        if (visitors.remove(visitorId) != null) {
            flush();
            log.info("Forgot everything held for one visitor — {} still remembered.", visitors.size());
        }
    }

    /**
     * Restores what previous runs learned. A corrupt file must never stop the application from
     * booting — the agent simply meets everyone as a stranger.
     */
    private void load() {
        if (!Files.isRegularFile(file)) {
            log.info("No long-term memory at {} — every visitor starts as a stranger.", file.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            JsonNode stored = root.path("visitors");
            stored.propertyNames().forEach(id -> visitors.put(id, readMemory(stored.path(id))));
            log.info("Restored long-term memory for {} visitor(s) from {}",
                    visitors.size(), file.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read long-term memory from {} ({}) — starting with none.",
                    file.toAbsolutePath(), e.getMessage());
            visitors.clear();
        }
    }

    /** Temporary file plus atomic rename, so a crash mid-write cannot truncate what is held. */
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
            throw new UncheckedIOException("Could not persist long-term memory to " + file.toAbsolutePath(), e);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode stored = root.putObject("visitors");
        visitors.forEach((id, memory) -> {
            if (!memory.isPresent()) {
                return;
            }
            ObjectNode node = stored.putObject(id);
            node.put("revision", memory.revision());
            ArrayNode array = node.putArray("entries");
            for (LongTermMemory.Entry entry : memory.entries()) {
                ObjectNode written = array.addObject();
                written.put("kind", entry.kind().id());
                written.put("key", entry.key());
                written.put("value", entry.value());
            }
        });
        return root;
    }

    /**
     * Entries whose kind no longer exists are dropped, not defaulted. Same reasoning as
     * {@link LongTermKind#from}: a file written by an older build should lose a line rather than
     * have it silently refiled under a lifetime nobody chose for it.
     */
    private static LongTermMemory readMemory(JsonNode node) {
        List<LongTermMemory.Entry> entries = new ArrayList<>();
        for (JsonNode held : node.path("entries")) {
            LongTermKind kind = LongTermKind.from(text(held.path("kind")));
            if (kind == null) {
                log.warn("Dropping a stored entry with unknown kind '{}'.", text(held.path("kind")));
                continue;
            }
            entries.add(new LongTermMemory.Entry(kind, text(held.path("key")), text(held.path("value"))));
        }
        return new LongTermMemory(entries, (int) number(node.path("revision")));
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }

    private static long number(JsonNode node) {
        return node.isNumber() ? node.longValue() : 0L;
    }
}
