package com.crispyland.agent.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The in-memory store plus a write-through, so a restart does not merge every branch back into
 * one. The refs are tiny and are rewritten whole on every change — there is no point streaming
 * a file that holds a handful of names.
 */
public class JsonFileBranchStore extends InMemoryBranchStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileBranchStore.class);

    private final ObjectMapper mapper;
    private final Path file;

    public JsonFileBranchStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
        load();
    }

    @Override
    synchronized void changed() {
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
            throw new UncheckedIOException("Could not persist branches to " + file.toAbsolutePath(), e);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode stored = root.putObject("conversations");
        branches.forEach((conversationId, forks) -> {
            ObjectNode entry = stored.putObject(conversationId);
            entry.put("active", active(conversationId));
            ArrayNode array = entry.putArray("branches");
            for (Branch branch : forks) {
                ObjectNode node = array.addObject();
                node.put("id", branch.id());
                node.put("name", branch.name());
                node.put("parentId", branch.parentId());
                node.put("forkedAt", branch.forkedAt());
            }
        });
        return root;
    }

    /** A missing or corrupt file means "nothing has been forked" — never a failure to boot. */
    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode stored = mapper.readTree(Files.readString(file)).path("conversations");
            stored.propertyNames().forEach(conversationId -> {
                JsonNode entry = stored.path(conversationId);
                List<Branch> forks = new ArrayList<>();
                for (JsonNode node : entry.path("branches")) {
                    forks.add(new Branch(text(node.path("id")), text(node.path("name")),
                            node.path("parentId").isTextual() ? node.path("parentId").stringValue() : null,
                            node.path("forkedAt").isNumber() ? node.path("forkedAt").intValue() : 0));
                }
                String open = text(entry.path("active"));
                if (!forks.isEmpty()) {
                    branches.put(conversationId, forks);
                    if (!open.isBlank()) {
                        active.put(conversationId, open);
                    }
                }
            });
            log.info("Restored branches for {} conversation(s) from {}", branches.size(), file.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read branches from {} ({}) — every conversation starts on its trunk.",
                    file.toAbsolutePath(), e.getMessage());
            branches.clear();
            active.clear();
        }
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }
}
