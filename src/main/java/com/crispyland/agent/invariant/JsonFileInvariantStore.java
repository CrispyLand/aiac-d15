package com.crispyland.agent.invariant;

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
 * Standing invariants on disk, in their own file.
 * <p>
 * Its own file for the same reason long-term memory has one — separate lifetimes should not share a
 * write path — and for one reason of its own: this is the file you would hand someone who asked
 * "what is this assistant not allowed to do?". A constraint buried inside the conversation log is
 * a constraint nobody can audit.
 * <p>
 * A corrupt or unreadable file must never stop the app booting, but it is louder here than
 * elsewhere. Losing remembered facts degrades the answers; losing invariants removes the rules
 * while the agent carries on sounding just as confident.
 */
public class JsonFileInvariantStore implements InvariantStore {

    private static final Logger log = LoggerFactory.getLogger(JsonFileInvariantStore.class);

    private final Map<String, Invariants> visitors = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Path file;

    public JsonFileInvariantStore(ObjectMapper mapper, Path file) {
        this.mapper = mapper;
        this.file = file;
        load();
    }

    @Override
    public Invariants held(String visitorId) {
        return visitors.getOrDefault(visitorId, Invariants.EMPTY);
    }

    @Override
    public Invariants declare(String visitorId, Invariant invariant) {
        Invariants updated = visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).with(invariant));
        flush();
        return updated;
    }

    @Override
    public Invariants retire(String visitorId, String id, String reason) {
        Invariants updated = visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).retire(id, reason));
        flush();
        return updated;
    }

    @Override
    public Invariants restore(String visitorId, String id) {
        Invariants updated = visitors.compute(visitorId, (key, held) ->
                (held == null ? Invariants.EMPTY : held).restore(id));
        flush();
        return updated;
    }

    @Override
    public void clear(String visitorId) {
        if (visitors.remove(visitorId) != null) {
            flush();
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            log.info("No invariants at {} — nothing is currently forbidden.", file.toAbsolutePath());
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            JsonNode stored = root.path("visitors");
            stored.propertyNames().forEach(id -> visitors.put(id, readInvariants(stored.path(id))));
            log.info("Restored invariants for {} visitor(s) from {}",
                    visitors.size(), file.toAbsolutePath());
        } catch (IOException | JacksonException e) {
            log.warn("Could not read invariants from {} ({}) — starting with none, so nothing "
                    + "will be refused until they are declared again.", file.toAbsolutePath(), e.getMessage());
            visitors.clear();
        }
    }

    /** Temporary file plus atomic rename, so a crash mid-write cannot truncate the rule set. */
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
            throw new UncheckedIOException("Could not persist invariants to " + file.toAbsolutePath(), e);
        }
    }

    private ObjectNode toJson() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode stored = root.putObject("visitors");
        visitors.forEach((id, invariants) -> {
            if (invariants.size() == 0) {
                return;
            }
            ObjectNode node = stored.putObject(id);
            node.put("revision", invariants.revision());
            ArrayNode array = node.putArray("invariants");
            for (Invariant held : invariants.all()) {
                ObjectNode written = array.addObject();
                written.put("id", held.id());
                written.put("kind", held.kind().id());
                written.put("scope", held.scope().id());
                written.put("check", held.check().id());
                written.put("rule", held.rule());
                written.put("why", held.why());
                written.put("instead", held.instead());
                written.put("active", held.active());
                written.put("retiredWhy", held.retiredWhy());
                ArrayNode terms = written.putArray("watch");
                held.watch().forEach(terms::add);
            }
        });
        return root;
    }

    /**
     * A stored rule whose kind or check mode no longer exists keeps its rule text and falls back to
     * a safe default, rather than being dropped.
     * <p>
     * This is the one place that deliberately differs from {@code JsonFileLongTermStore}, which
     * drops entries it cannot classify. Dropping a remembered fact loses a detail; dropping an
     * invariant silently removes a constraint the user believes is still in force, and they will
     * not find out until the agent proposes the thing they forbade.
     */
    private static Invariants readInvariants(JsonNode node) {
        List<Invariant> held = new ArrayList<>();
        for (JsonNode stored : node.path("invariants")) {
            InvariantKind kind = InvariantKind.from(text(stored.path("kind")));
            if (kind == null) {
                log.warn("Invariant '{}' has unknown kind '{}' — keeping it as a technical decision.",
                        text(stored.path("id")), text(stored.path("kind")));
                kind = InvariantKind.TECHNICAL;
            }
            Check check = Check.from(text(stored.path("check")));
            if (check == null) {
                log.warn("Invariant '{}' has unknown check '{}' — falling back to asking the model.",
                        text(stored.path("id")), text(stored.path("check")));
                check = Check.MODEL;
            }
            List<String> watch = new ArrayList<>();
            for (JsonNode term : stored.path("watch")) {
                watch.add(text(term));
            }
            held.add(new Invariant(text(stored.path("id")), kind,
                    InvariantScope.from(text(stored.path("scope"))), check,
                    text(stored.path("rule")), text(stored.path("why")),
                    text(stored.path("instead")), watch,
                    !stored.path("active").isBoolean() || stored.path("active").booleanValue(),
                    text(stored.path("retiredWhy"))));
        }
        return new Invariants(held, (int) number(node.path("revision")));
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.stringValue() : "";
    }

    private static long number(JsonNode node) {
        return node.isNumber() ? node.longValue() : 0L;
    }
}
