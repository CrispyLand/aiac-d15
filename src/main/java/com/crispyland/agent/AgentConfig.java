package com.crispyland.agent;

import java.util.List;
import java.util.Objects;

/**
 * Immutable value object carrying every tunable request parameter.
 * <p>
 * Any field may be {@code null} (or blank), meaning "not supplied by the caller"; the
 * {@link #withFallback(AgentConfig)} merge fills those gaps from the configured defaults.
 * Adding a new tunable means: add a component here, one line in the builder, one line in
 * {@code withFallback}, one line in the yml defaults.
 * <p>
 * Not all of it is sent to the provider: {@code maxCompletionTokens} doubles as the slice of
 * the window reserved for the reply, and {@code compressHistory} never leaves the process at
 * all — it decides how the context is assembled, which is what makes it worth having on the
 * page as a switch you can flip between two otherwise identical turns.
 */
public record AgentConfig(
        String model,
        String systemPrompt,
        Double temperature,
        Integer maxCompletionTokens,
        String reasoningEffort,
        List<String> stopSequences,
        String responseSchema,
        Boolean compressHistory) {

    public AgentConfig {
        stopSequences = (stopSequences == null) ? null : List.copyOf(stopSequences);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .temperature(temperature)
                .maxCompletionTokens(maxCompletionTokens)
                .reasoningEffort(reasoningEffort)
                .stopSequences(stopSequences)
                .responseSchema(responseSchema)
                .compressHistory(compressHistory);
    }

    /**
     * Returns a copy of this config where every value the caller left unset is taken from
     * {@code defaults}. This is what makes the page "send only what it wants to override".
     */
    public AgentConfig withFallback(AgentConfig defaults) {
        Objects.requireNonNull(defaults, "defaults");
        return new AgentConfig(
                text(model, defaults.model()),
                text(systemPrompt, defaults.systemPrompt()),
                temperature != null ? temperature : defaults.temperature(),
                maxCompletionTokens != null ? maxCompletionTokens : defaults.maxCompletionTokens(),
                text(reasoningEffort, defaults.reasoningEffort()),
                stopSequences != null ? stopSequences : defaults.stopSequences(),
                text(responseSchema, defaults.responseSchema()),
                compressHistory != null ? compressHistory : defaults.compressHistory());
    }

    /** Compression is opt-out rather than opt-in once the defaults have been merged in. */
    public boolean compressHistoryEnabled() {
        return Boolean.TRUE.equals(compressHistory);
    }

    public List<String> stopSequencesOrEmpty() {
        return stopSequences == null ? List.of() : stopSequences;
    }

    public boolean hasResponseSchema() {
        return responseSchema != null && !responseSchema.isBlank();
    }

    public boolean hasReasoningEffort() {
        return reasoningEffort != null && !reasoningEffort.isBlank();
    }

    private static String text(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public static final class Builder {
        private String model;
        private String systemPrompt;
        private Double temperature;
        private Integer maxCompletionTokens;
        private String reasoningEffort;
        private List<String> stopSequences;
        private String responseSchema;
        private Boolean compressHistory;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxCompletionTokens(Integer maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        public Builder reasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        public Builder stopSequences(List<String> stopSequences) {
            this.stopSequences = stopSequences;
            return this;
        }

        public Builder responseSchema(String responseSchema) {
            this.responseSchema = responseSchema;
            return this;
        }

        public Builder compressHistory(Boolean compressHistory) {
            this.compressHistory = compressHistory;
            return this;
        }

        public AgentConfig build() {
            return new AgentConfig(model, systemPrompt, temperature, maxCompletionTokens,
                    reasoningEffort, stopSequences, responseSchema, compressHistory);
        }
    }
}
