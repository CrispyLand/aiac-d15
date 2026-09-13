package com.crispyland.agent.usage;

/** What the agent does when the next call will not fit in the model's context window. */
public enum OverflowPolicy {

    /**
     * Measure and report, but send anyway. The provider rejects the call with
     * {@code context_length_exceeded} — useful to see what an unguarded agent actually
     * experiences, and what it costs (nothing is generated, but the round trip is spent).
     */
    OFF,

    /**
     * Refuse locally before the call is made. The cheapest failure available, and the
     * honest one: the user is told the arithmetic instead of a provider stack trace.
     * The dialogue is then stuck until it is reset or shortened — which is the real
     * consequence a message-counted window hides.
     */
    FAIL,

    /**
     * Drop the oldest turns until the call fits. What production assistants do, and why
     * a long chat silently forgets its own beginning rather than erroring. Still fails
     * if the system prompt and the new message alone cannot fit.
     */
    TRIM
}
