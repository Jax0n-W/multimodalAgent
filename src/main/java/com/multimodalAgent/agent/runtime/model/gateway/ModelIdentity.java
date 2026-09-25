package com.multimodalAgent.agent.runtime.model.gateway;

public record ModelIdentity(String provider, String model) {

    public ModelIdentity {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }
}
