package com.multimodalAgent.agent.runtime.budget;

import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public final class ModelCostCalculator {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);

    public Optional<BigDecimal> calculate(
            ModelIdentity identity,
            TokenUsage usage,
            ModelPricing pricing
    ) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(pricing, "pricing must not be null");
        if (!identity.equals(pricing.identity()) || !usage.isComplete()) {
            return Optional.empty();
        }
        BigDecimal inputCost = pricing.inputCostPerMillionTokens()
                .multiply(BigDecimal.valueOf(usage.inputTokens()))
                .divide(ONE_MILLION);
        BigDecimal outputCost = pricing.outputCostPerMillionTokens()
                .multiply(BigDecimal.valueOf(usage.outputTokens()))
                .divide(ONE_MILLION);
        return Optional.of(inputCost.add(outputCost));
    }
}
