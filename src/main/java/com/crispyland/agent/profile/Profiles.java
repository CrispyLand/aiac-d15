package com.crispyland.agent.profile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads the profile directory: {@code users/*.yml} and {@code lenses/*.yml}.
 * <p>
 * Files rather than a database or a config block, because the point of the feature is that the
 * person being personalized for can change how the agent treats them without a deploy. They live
 * outside {@code src/main/resources} deliberately — packaged into the jar they would be read-only
 * at exactly the moment someone wants to edit them.
 * <p>
 * Read fresh on every lookup, not cached. Two sub-kilobyte files per turn is nothing next to the
 * network call that follows, and in exchange editing a profile takes effect on the next message
 * instead of the next restart. That is the same trade the system prompt already makes by being
 * re-applied each turn rather than stored.
 * <p>
 * Parsed with {@link SafeConstructor}, which will not instantiate arbitrary classes from YAML
 * tags. These files are a boundary — hand-edited, outside the source tree, and the one input to
 * the agent that is not either code or a message — so they are validated here and nowhere else:
 * a malformed file is logged and skipped rather than thrown, because one bad profile should cost
 * its owner their personalization and not take the service down for everyone.
 */
public class Profiles {

    private static final Logger log = LoggerFactory.getLogger(Profiles.class);

    private final Path root;

    public Profiles(Path root) {
        this.root = root;
    }

    /** Every readable user profile, by id. Never null, possibly empty. */
    public List<UserProfile> users() {
        return load(root.resolve("users"), Profiles::readUser);
    }

    public List<Lens> lenses() {
        return load(root.resolve("lenses"), Profiles::readLens);
    }

    public UserProfile user(String id) {
        return first(users(), id, UserProfile::id);
    }

    public Lens lens(String id) {
        return first(lenses(), id, Lens::id);
    }

    private static <T> T first(List<T> all, String id, java.util.function.Function<T, String> key) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        return all.stream().filter(item -> wanted.equals(key.apply(item))).findFirst().orElse(null);
    }

    private <T> List<T> load(Path directory, java.util.function.BiFunction<String, Map<String, Object>, T> reader) {
        if (!Files.isDirectory(directory)) {
            log.warn("No profile directory at {} — nobody gets personalized until it exists.",
                    directory.toAbsolutePath());
            return List.of();
        }
        List<T> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sorted = files.filter(Profiles::isYaml).sorted(Comparator.comparing(Path::getFileName)).toList();
            for (Path file : sorted) {
                T parsed = parse(file, reader);
                if (parsed != null) {
                    loaded.add(parsed);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list {} ({}) — continuing with no profiles from it.",
                    directory.toAbsolutePath(), e.getMessage());
        }
        return List.copyOf(loaded);
    }

    private static <T> T parse(Path file, java.util.function.BiFunction<String, Map<String, Object>, T> reader) {
        try (Reader text = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            if (!(raw instanceof Map<?, ?> map)) {
                log.warn("{} is not a YAML mapping — skipping it.", file.getFileName());
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) map;
            // The filename is the id unless the file overrides it, so two files can never claim
            // the same id by accident and the switcher's links always resolve to something.
            return reader.apply(stem(file), fields);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read profile {} ({}) — skipping it.", file.getFileName(), e.getMessage());
            return null;
        }
    }

    private static UserProfile readUser(String stem, Map<String, Object> fields) {
        Map<String, Object> format = map(fields.get("format"));
        return new UserProfile(
                text(fields.get("id"), stem),
                text(fields.get("display-name"), stem),
                text(fields.get("language"), null),
                text(fields.get("tone"), null),
                new UserProfile.Format(text(format.get("shape"), null), integer(format.get("max-words"), 0)),
                list(fields.get("never")),
                limits(fields.get("limits")),
                text(fields.get("default-lens"), null));
    }

    private static Lens readLens(String stem, Map<String, Object> fields) {
        return new Lens(
                text(fields.get("id"), stem),
                text(fields.get("title"), stem),
                text(fields.get("lens"), null),
                list(fields.get("forbids")),
                list(fields.get("keywords")),
                limits(fields.get("limits")));
    }

    private static Limits limits(Object raw) {
        Map<String, Object> fields = map(raw);
        Integer maxTokens = (fields.get("max-completion-tokens") == null)
                ? null : integer(fields.get("max-completion-tokens"), 0);
        Double temperature = (fields.get("temperature") instanceof Number number)
                ? number.doubleValue() : null;
        // "" is a meaningful value here — it omits the parameter — so an explicitly empty string
        // is kept and only an absent key falls through to the lens and then the defaults.
        String reasoning = fields.containsKey("reasoning-effort")
                ? String.valueOf(fields.get("reasoning-effort")).strip() : null;
        return new Limits((maxTokens != null && maxTokens > 0) ? maxTokens : null, temperature,
                reasoning);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return ((dot <= 0) ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(file) && (name.endsWith(".yml") || name.endsWith(".yaml"));
    }

    private static String text(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).strip();
        return value.isEmpty() ? fallback : value;
    }

    private static int integer(Object raw, int fallback) {
        return (raw instanceof Number number) ? number.intValue() : fallback;
    }

    private static Map<String, Object> map(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) map;
            return fields;
        }
        return Map.of();
    }

    /** Tolerant of a single string where a list was meant — a one-item rule reads better unbracketed. */
    private static List<String> list(Object raw) {
        if (raw instanceof List<?> values) {
            return values.stream().filter(java.util.Objects::nonNull)
                    .map(value -> String.valueOf(value).strip())
                    .filter(value -> !value.isEmpty()).toList();
        }
        String single = text(raw, null);
        return (single == null) ? List.of() : List.of(single);
    }
}
