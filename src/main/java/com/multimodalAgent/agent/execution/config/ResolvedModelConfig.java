package com.multimodalAgent.agent.execution.config;

import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable model settings shared by the executing adapter and snapshot resolver. */
public record ResolvedModelConfig(
        ModelIdentity identity,
        BigDecimal temperature,
        int maxTokensPerInvocation,
        ModelTimeoutPolicy timeoutPolicy
) {

    public ResolvedModelConfig {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(temperature, "temperature must not be null");
        Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        if (temperature.signum() < 0) {
            throw new IllegalArgumentException("temperature must not be negative");
        }
        if (maxTokensPerInvocation < 1) {
            throw new IllegalArgumentException("maxTokensPerInvocation must be at least 1");
        }
        temperature = normalize(temperature);
    }

    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
    }
}
