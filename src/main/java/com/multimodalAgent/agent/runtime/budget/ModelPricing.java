package com.multimodalAgent.agent.runtime.budget;

import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;

import java.math.BigDecimal;
import java.util.Objects;

public record ModelPricing(
        ModelIdentity identity,
        BigDecimal inputCostPerMillionTokens,
        BigDecimal outputCostPerMillionTokens
) {

    public ModelPricing {
        Objects.requireNonNull(identity, "identity must not be null");
        requireNonNegative(inputCostPerMillionTokens, "inputCostPerMillionTokens");
        requireNonNegative(outputCostPerMillionTokens, "outputCostPerMillionTokens");
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
