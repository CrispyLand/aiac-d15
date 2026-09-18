package com.crispyland.agent.profile;

import com.crispyland.agent.AgentConfig;
import java.util.List;

/**
 * A {@link UserProfile} wearing a {@link Lens} — the personalization actually in force for one
 * request, and the only form of it the rest of the agent ever sees.
 * <p>
 * Either half may be absent. A user with no lens is the ordinary case (someone who just wants
 * their own language and length honoured); a lens with no user is what the side-by-side runner
 * produces when it is isolating the lens's contribution. Both absent is
 * {@link #NONE}, which renders nothing and changes no request parameter, so every existing test
 * and every unprofiled visitor keeps working exactly as before.
 *
 * @param user who is asking and how they want to be answered; may be null
 * @param lens which hat to wear for this job; may be null
 */
public record Persona(UserProfile user, Lens lens) {

    public static final Persona NONE = new Persona(null, null);

    public static Persona of(UserProfile user) {
        return new Persona(user, null);
    }

    public boolean hasUser() {
        return user != null && user.isPresent();
    }

    public boolean hasLens() {
        return lens != null && lens.isPresent();
    }

    public boolean isPresent() {
        return hasUser() || hasLens();
    }

    /** For the page and the logs: "Russell · chemist", or whichever half exists. */
    public String label() {
        if (hasUser() && hasLens()) {
            return user.label() + " · " + lens.id();
        }
        return hasUser() ? user.label() : (hasLens() ? lens.id() : "none");
    }

    /**
     * The block as it is sent to the model.
     * <p>
     * Two things about the wording are load-bearing. First, it says these preferences were set
     * deliberately and outside this conversation — without that the model treats them as just
     * more context and trades them off against everything else it was told. Second, it says
     * outright that they outrank what is remembered, because they will eventually contradict it:
     * the extractor files a {@code profile/preference} line the moment anyone mentions liking
     * something, so a stated preference from six weeks ago sits in long-term memory arguing with
     * the file the user edited this morning. Inferred loses to declared, and the prompt has to say
     * which is which or the model has no way to tell them apart.
     * <p>
     * The forbids are last because that is the position the model weights most heavily, and they
     * are the lines whose violation costs the most.
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        if (hasUser()) {
            text.append("HOW THIS PERSON WANTS TO BE ANSWERED. They set this themselves, "
                    + "outside this conversation, and it applies to every reply. Where it "
                    + "disagrees with anything you remember about them, this wins — it is what "
                    + "they asked for, not what you inferred.");
            line(text, "Name", user.label());
            line(text, "Answer in", user.language());
            line(text, "Tone", user.tone());
            if (user.format().isPresent()) {
                line(text, "Format", user.format().render());
            }
            list(text, "Never", user.never());
        }
        if (hasLens()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append("THE ROLE YOU ARE ANSWERING IN — ").append(lens.label()).append('.');
            if (lens.lens() != null && !lens.lens().isBlank()) {
                text.append('\n').append(lens.lens().strip());
            }
            list(text, "Never, in this role", lens.forbids());
        }
        return text.toString();
    }

    /**
     * Folds the profile's enforced half into the request parameters.
     * <p>
     * The user's limits beat the lens's, and both are applied only where the caller left the value
     * unset — so an explicit change on the page still wins over the file. That ordering follows
     * from what each layer is: the page is someone overriding this one request on purpose, the
     * profile is a standing preference, and the yml is what to do when nobody said. Deciding it
     * per-field instead (say, always taking the smaller ceiling) would read as safer and be worse:
     * a rule that differs by field is one nobody can predict the outcome of.
     */
    public AgentConfig applyTo(AgentConfig config) {
        Limits effective = limits();
        if (!effective.isPresent()) {
            return config;
        }
        return config.withFallback(AgentConfig.builder()
                .maxCompletionTokens(effective.maxCompletionTokens())
                .temperature(effective.temperature())
                .reasoningEffort(effective.reasoningEffort())
                .build());
    }

    /** User over lens, each field independently. */
    public Limits limits() {
        Limits fromUser = hasUser() ? user.limits() : Limits.NONE;
        Limits fromLens = hasLens() ? lens.limits() : Limits.NONE;
        return fromUser.over(fromLens);
    }

    private static void line(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        text.append('\n').append(label).append(": ").append(value.strip());
    }

    private static void list(StringBuilder text, String label, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        text.append('\n').append(label).append(':');
        for (String value : values) {
            text.append("\n- ").append(value.strip());
        }
    }
}
