package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.budget.BudgetUsage;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Immutable, serializable budget state; the mutable BudgetSession is never persisted. */
public record BudgetCheckpoint(
        long modelCalls,
        long toolCalls,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        Optional<BigDecimal> cost,
        boolean unknownUsageObserved
) {

    public static final BudgetCheckpoint EMPTY = new BudgetCheckpoint(
            0, 0, 0, 0, 0, Optional.empty(), false
    );

    public BudgetCheckpoint {
        requireNonNegative(modelCalls, "modelCalls");
        requireNonNegative(toolCalls, "toolCalls");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(totalTokens, "totalTokens");
        if (totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("totalTokens must equal inputTokens + outputTokens");
        }
        Objects.requireNonNull(cost, "cost must not be null");
        cost.ifPresent(value -> {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("cost must not be negative");
            }
        });
    }

    public static BudgetCheckpoint from(BudgetUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        return new BudgetCheckpoint(
                usage.modelCalls(), usage.toolCalls(), usage.inputTokens(), usage.outputTokens(),
                usage.totalTokens(), usage.cost(), usage.unknownUsageObserved()
        );
    }

    public BudgetUsage toBudgetUsage() {
        return new BudgetUsage(
                modelCalls, toolCalls, inputTokens, outputTokens, totalTokens,
                cost, unknownUsageObserved
        );
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
