package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.budget.BudgetUsage;

import java.util.Objects;

/** Reconstructed immutable budget state suitable for checkpointing and session restoration. */
public record RecoveredBudgetUsage(BudgetUsage usage) {

    public RecoveredBudgetUsage {
        Objects.requireNonNull(usage, "usage must not be null");
    }

    public BudgetCheckpoint toCheckpoint() {
        return BudgetCheckpoint.from(usage);
    }
}
