package com.multimodalAgent.agent.runtime.budget;

import java.util.Objects;

public final class BudgetBlockedException extends RuntimeException {

    private final BudgetBlock block;

    public BudgetBlockedException(BudgetBlock block) {
        super("Execution budget " + Objects.requireNonNull(block, "block must not be null").reason()
                + ": " + block.dimension());
        this.block = block;
    }

    public BudgetBlock block() {
        return block;
    }
}
