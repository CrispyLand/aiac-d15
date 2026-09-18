package com.crispyland.agent.profile;

import java.util.List;

/**
 * How one person wants to be answered, stated by them rather than inferred by the agent.
 * <p>
 * This is the distinction the whole package rests on, and it is not the same as
 * {@link com.crispyland.agent.memory.LongTermKind#PROFILE}. That is what the agent has
 * <em>noticed</em> about someone from things they happened to say; this is what they have
 * <em>declared</em>, in a file they edit, applied identically on every request no matter what the
 * conversation has been about. One is an observation and can be wrong; the other is an
 * instruction and cannot. When they disagree, the declared one wins — see {@link Persona#render}.
 * <p>
 * Deliberately not stored in the {@link com.crispyland.agent.memory.MemoryState}. Everything in
 * there has a lifetime and is discarded at some boundary; a profile has no lifetime, because it is
 * not something the agent learned and so not something that can go stale on its own.
 *
 * @param id          the file's stem, and the handle the switcher posts back
 * @param displayName what to call them
 * @param language    the language to answer in, whatever language the question arrived in
 * @param tone        formal, casual — one word, rendered verbatim
 * @param format      shape and length of the reply
 * @param never       hard "do not" lines, rendered into the block as the user wrote them
 * @param limits      the part that is enforced rather than asked for
 * @param defaultLens the lens to wear when nothing else selects one; may be null
 */
public record UserProfile(String id, String displayName, String language, String tone,
                          Format format, List<String> never, Limits limits, String defaultLens) {

    /**
     * Shape and length.
     *
     * @param shape    "bullets", "prose" — free text, since the useful vocabulary here is the
     *                 user's own and an enum would only force them to say it our way
     * @param maxWords soft target rendered into the prompt; the hard one is
     *                 {@link Limits#maxCompletionTokens}, because words are not something the
     *                 request can cap and tokens are
     */
    public record Format(String shape, int maxWords) {

        public static final Format NONE = new Format(null, 0);

        public boolean isPresent() {
            return (shape != null && !shape.isBlank()) || maxWords > 0;
        }

        public String render() {
            if (shape == null || shape.isBlank()) {
                return "at most " + maxWords + " words";
            }
            return (maxWords > 0) ? shape + ", at most " + maxWords + " words" : shape;
        }
    }

    public UserProfile {
        never = (never == null) ? List.of() : List.copyOf(never);
        format = (format == null) ? Format.NONE : format;
        limits = (limits == null) ? Limits.NONE : limits;
    }

    public boolean isPresent() {
        return id != null && !id.isBlank();
    }

    public String label() {
        return (displayName == null || displayName.isBlank()) ? id : displayName;
    }
}
