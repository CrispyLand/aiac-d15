package com.crispyland.agent.profile;

/**
 * The half of a profile that is enforced rather than requested.
 * <p>
 * Everything else in a profile is prose that ends up in the prompt, and prose is a request: a
 * model asked for "short answers" will still write six paragraphs when the question invites it.
 * These fields become actual request parameters instead, so "short" stops being a preference the
 * model weighs against everything else it was told and becomes a ceiling it cannot reach past.
 * <p>
 * Null means "not stated by this profile", not "zero" — an unset field falls through to the lens,
 * and then to the application defaults, via {@link Persona#applyTo}. That is why these are boxed
 * types and not primitives.
 *
 * @param maxCompletionTokens hard ceiling on the reply, and the slice of the window reserved for it
 * @param temperature         lower for profiles that want precision over voice
 * @param reasoningEffort     how much of that ceiling the model may spend thinking before it
 *                            answers. Here rather than only in the yml defaults because it is
 *                            inseparable from the ceiling above it: on gpt-oss the hidden
 *                            reasoning tokens are billed against {@code max_completion_tokens} and
 *                            are spent <em>first</em>, so a profile that asks for short answers
 *                            without also lowering this does not get a short answer — it gets a
 *                            truncated monologue. Observed exactly that at 400 tokens with the
 *                            parameter omitted: the reply printed was the chain of thought, cut
 *                            off mid-sentence. A profile that tightens one must be able to tighten
 *                            the other. Blank defers to the defaults rather than forcing the
 *                            parameter off — every string in {@code AgentConfig} reads blank as
 *                            "not stated", and a profile does not get its own rule
 */
public record Limits(Integer maxCompletionTokens, Double temperature, String reasoningEffort) {

    public static final Limits NONE = new Limits(null, null, null);

    /** This one's values where it states them, otherwise {@code weaker}'s. */
    public Limits over(Limits weaker) {
        if (weaker == null) {
            return this;
        }
        return new Limits(
                (maxCompletionTokens != null) ? maxCompletionTokens : weaker.maxCompletionTokens(),
                (temperature != null) ? temperature : weaker.temperature(),
                (reasoningEffort != null) ? reasoningEffort : weaker.reasoningEffort());
    }

    public boolean isPresent() {
        return maxCompletionTokens != null || temperature != null || reasoningEffort != null;
    }
}
