package com.crispyland.agent.profile;

import java.util.List;
import java.util.Locale;

/**
 * The hat the agent wears for a particular kind of job — the domain it is speaking from, and what
 * it must not do while speaking from it.
 * <p>
 * Called a lens rather than a "task profile" on purpose. The codebase already has two things
 * called profile — {@link UserProfile} and the long-term memory kind — and a third would make
 * every sentence about precedence ambiguous at exactly the point where precedence is the thing
 * being explained.
 * <p>
 * A lens is orthogonal to a user: the same person asks about chemistry on Monday and about
 * interest rates on Tuesday and wants both answered in their own language, tone and length. That
 * is why the two are separate files combined at request time by {@link Persona}, rather than one
 * file per combination — five users and five lenses would otherwise be twenty-five files, and
 * changing how one person likes to be addressed would mean editing five of them.
 *
 * @param id       the file's stem, and what {@code /lens chemist} matches
 * @param title    one line for the switcher and the logs
 * @param lens     the domain description, sent as-is; the substance of the block
 * @param forbids  hard "not in this role" lines — no diagnoses, no investment advice
 * @param keywords lowercase triggers for automatic selection when nothing was picked explicitly
 * @param limits   lens-level enforcement, weaker than the user's; see {@link Persona#applyTo}
 */
public record Lens(String id, String title, String lens, List<String> forbids,
                   List<String> keywords, Limits limits) {

    public Lens {
        forbids = (forbids == null) ? List.of() : List.copyOf(forbids);
        keywords = (keywords == null) ? List.of() : normalized(keywords);
        limits = (limits == null) ? Limits.NONE : limits;
    }

    public boolean isPresent() {
        return id != null && !id.isBlank();
    }

    public String label() {
        return (title == null || title.isBlank()) ? id : title;
    }

    /**
     * Whether this lens claims the message, and which word did it.
     * <p>
     * Returns the matched keyword rather than a boolean so the selection can be logged with its
     * reason. "Chose economist" is not reviewable; "chose economist on 'инфляц'" is — and when the
     * router picks something absurd, the word that caused it is the only thing worth knowing.
     */
    public String claim(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String haystack = message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private static List<String> normalized(List<String> keywords) {
        return keywords.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.strip().toLowerCase(Locale.ROOT))
                .toList();
    }
}
