package com.crispyland.agent.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which user profile and which lens are in force, and says why.
 * <p>
 * Three sources, tried in order: what was picked explicitly, what the message itself suggests,
 * and the user's stated default. Explicit first is not arbitrary — a keyword rule that can
 * override a deliberate choice is a rule that occasionally ignores the person it is serving, and
 * the failure is invisible because the answer still looks plausible, just written by the wrong
 * specialist.
 * <p>
 * The keyword match is deliberately the crudest thing that works: lowercase substring, first lens
 * to claim it wins, ordered by filename. Asking a model which lens to use would cost a call per
 * turn, add a second place where behaviour is decided by something non-deterministic, and be
 * wrong in ways nobody could reproduce. A substring match is wrong in ways you can read off a
 * single log line and fix by editing one keyword list.
 */
public class PersonaSelector {

    private static final Logger log = LoggerFactory.getLogger(PersonaSelector.class);

    /**
     * What was chosen and what chose it.
     *
     * @param persona the two halves in force for this request
     * @param why     human-readable reason, logged and shown on the page — "picked", "matched
     *                'инфляц'", "russell's default". The reason exists because a lens changes what
     *                the agent refuses to do, and a silent refusal that traces back to an
     *                unexplained selection is the hardest kind of behaviour to debug
     */
    public record Selection(Persona persona, String why) {

        public static final Selection NONE = new Selection(Persona.NONE, "no profile");

        public UserProfile user() {
            return persona.user();
        }

        public Lens lens() {
            return persona.lens();
        }
    }

    private final Profiles profiles;

    public PersonaSelector(Profiles profiles) {
        this.profiles = profiles;
    }

    /**
     * Resolves the persona for one turn.
     *
     * @param userId   who is asking; null or unknown means nobody is personalized
     * @param lensId   an explicit pick, or null to let the message and the default decide
     * @param message  the new user message, used only for the keyword fallback. May be null on a
     *                 page load, which is exactly why the default exists
     */
    public Selection select(String userId, String lensId, String message) {
        UserProfile user = profiles.user(userId);
        if (user == null && (userId != null && !userId.isBlank())) {
            // Naming a profile that is not there is a typo or a deleted file, and answering as if
            // no profile had been asked for hides it. Neither is worth failing the turn over.
            log.warn("No user profile '{}' in the profiles directory — answering unpersonalized.", userId);
        }

        Lens picked = profiles.lens(lensId);
        if (picked != null) {
            return report(new Selection(new Persona(user, picked), "picked"));
        }

        for (Lens lens : profiles.lenses()) {
            String word = lens.claim(message);
            if (word != null) {
                return report(new Selection(new Persona(user, lens), "matched '" + word + "'"));
            }
        }

        if (user != null && user.defaultLens() != null) {
            Lens fallback = profiles.lens(user.defaultLens());
            if (fallback != null) {
                return report(new Selection(new Persona(user, fallback), user.id() + "'s default"));
            }
            log.warn("{} defaults to lens '{}', which no file defines — answering with no lens.",
                    user.id(), user.defaultLens());
        }

        return (user == null) ? Selection.NONE
                : report(new Selection(Persona.of(user), "no lens matched"));
    }

    private static Selection report(Selection selection) {
        log.info("Persona: {} ({})", selection.persona().label(), selection.why());
        return selection;
    }
}
