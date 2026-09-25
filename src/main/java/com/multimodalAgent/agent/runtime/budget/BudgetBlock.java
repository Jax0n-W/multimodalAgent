package com.multimodalAgent.agent.runtime.budget;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record BudgetBlock(
        BudgetDimension dimension,
        BudgetBlockReason reason,
        BigDecimal limit,
        Optional<BigDecimal> observed
) {

    public BudgetBlock {
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        if (limit.signum() < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        observed.ifPresent(value -> {
            if (value.signum() < 0) {
                throw new IllegalArgumentException("observed must not be negative");
            }
        });
        if (reason == BudgetBlockReason.EXHAUSTED && observed.isEmpty()) {
            throw new IllegalArgumentException("exhausted budget requires an observed value");
        }
    }

    public static BudgetBlock exhausted(
            BudgetDimension dimension,
            long limit,
            long observed
    ) {
        return exhausted(dimension, BigDecimal.valueOf(limit), BigDecimal.valueOf(observed));
    }

    public static BudgetBlock exhausted(
            BudgetDimension dimension,
            BigDecimal limit,
            BigDecimal observed
    ) {
        return new BudgetBlock(
                dimension, BudgetBlockReason.EXHAUSTED, limit, Optional.of(observed)
        );
    }

    public static BudgetBlock unverifiable(BudgetDimension dimension, BigDecimal limit) {
        return new BudgetBlock(
                dimension, BudgetBlockReason.UNVERIFIABLE, limit, Optional.empty()
        );
    }
}
