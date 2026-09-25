package com.multimodalAgent.agent.runtime.budget;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record ExecutionBudget(
        OptionalLong maxModelCalls,
        OptionalLong maxToolCalls,
        OptionalLong maxInputTokens,
        OptionalLong maxOutputTokens,
        OptionalLong maxTotalTokens,
        Optional<BigDecimal> maxCost,
        Optional<ModelPricing> pricing
) {

    public ExecutionBudget {
        requireNonNegative(maxModelCalls, "maxModelCalls");
        requireNonNegative(maxToolCalls, "maxToolCalls");
        requireNonNegative(maxInputTokens, "maxInputTokens");
        requireNonNegative(maxOutputTokens, "maxOutputTokens");
        requireNonNegative(maxTotalTokens, "maxTotalTokens");
        Objects.requireNonNull(maxCost, "maxCost must not be null");
        Objects.requireNonNull(pricing, "pricing must not be null");
        maxCost.ifPresent(value -> {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("maxCost must not be negative");
            }
        });
    }

    public static ExecutionBudget unlimited() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasTokenLimit() {
        return maxInputTokens.isPresent()
                || maxOutputTokens.isPresent()
                || maxTotalTokens.isPresent();
    }

    private static void requireNonNegative(OptionalLong value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isPresent() && value.getAsLong() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    public static final class Builder {

        private OptionalLong maxModelCalls = OptionalLong.empty();
        private OptionalLong maxToolCalls = OptionalLong.empty();
        private OptionalLong maxInputTokens = OptionalLong.empty();
        private OptionalLong maxOutputTokens = OptionalLong.empty();
        private OptionalLong maxTotalTokens = OptionalLong.empty();
        private Optional<BigDecimal> maxCost = Optional.empty();
        private Optional<ModelPricing> pricing = Optional.empty();

        public Builder maxModelCalls(long value) {
            maxModelCalls = OptionalLong.of(value);
            return this;
        }

        public Builder maxToolCalls(long value) {
            maxToolCalls = OptionalLong.of(value);
            return this;
        }

        public Builder maxInputTokens(long value) {
            maxInputTokens = OptionalLong.of(value);
            return this;
        }

        public Builder maxOutputTokens(long value) {
            maxOutputTokens = OptionalLong.of(value);
            return this;
        }

        public Builder maxTotalTokens(long value) {
            maxTotalTokens = OptionalLong.of(value);
            return this;
        }

        public Builder maxCost(BigDecimal value) {
            maxCost = Optional.of(Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        public Builder pricing(ModelPricing value) {
            pricing = Optional.of(Objects.requireNonNull(value, "value must not be null"));
            return this;
        }

        public ExecutionBudget build() {
            return new ExecutionBudget(
                    maxModelCalls, maxToolCalls, maxInputTokens, maxOutputTokens,
                    maxTotalTokens, maxCost, pricing
            );
        }
    }
}
