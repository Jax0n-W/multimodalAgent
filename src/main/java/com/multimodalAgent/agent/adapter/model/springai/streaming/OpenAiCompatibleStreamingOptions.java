package com.multimodalAgent.agent.adapter.model.springai.streaming;

/** Configuration for one OpenAI-compatible streaming model adapter. */
public record OpenAiCompatibleStreamingOptions(
        String providerName,
        String model,
        double temperature,
        int maxTokens
) {

    public OpenAiCompatibleStreamingOptions {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (!Double.isFinite(temperature) || temperature < 0.0) {
            throw new IllegalArgumentException("temperature must be finite and non-negative");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1");
        }
    }
}
