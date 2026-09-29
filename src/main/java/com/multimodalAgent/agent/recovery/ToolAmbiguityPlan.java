package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolAmbiguityPlan(
        ToolAmbiguityAction action,
        Optional<String> reconciliationStrategyId
) {

    public ToolAmbiguityPlan {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(
                reconciliationStrategyId,
                "reconciliationStrategyId must not be null"
        );
        if ((action == ToolAmbiguityAction.RECONCILE)
                != reconciliationStrategyId.isPresent()) {
            throw new IllegalArgumentException(
                    "Only reconciliation plans must carry a strategy identity"
            );
        }
    }
}
