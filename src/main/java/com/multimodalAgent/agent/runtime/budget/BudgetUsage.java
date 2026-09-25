package com.multimodalAgent.agent.runtime.budget;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Immutable diagnostic snapshot of work actually started by one run. */
public record BudgetUsage(
        long modelCalls,
        long toolCalls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        Optional<BigDecimal> cost,
        boolean unknownUsageObserved
) {

    public BudgetUsage {
        requireNonNegative(modelCalls, "modelCalls");
        requireNonNegative(toolCalls, "toolCalls");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(totalTokens, "totalTokens");
        Objects.requireNonNull(cost, "cost must not be null");
        cost.ifPresent(value -> {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("cost must not be negative");
            }
        });
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
