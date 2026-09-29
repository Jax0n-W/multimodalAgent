package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolReconciliationResolution(
        ToolUnknownMaterializationResult materialization,
        Optional<ToolAmbiguityPlan> plan,
        ToolReconciliationResolutionStatus status,
        Optional<ToolReconciliationAttempt> attempt
) {

    public ToolReconciliationResolution {
        Objects.requireNonNull(materialization, "materialization must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        if ((status == ToolReconciliationResolutionStatus.NOT_AMBIGUOUS) != plan.isEmpty()) {
            throw new IllegalArgumentException("Resolution plan availability is inconsistent");
        }
        boolean attemptRequired = status == ToolReconciliationResolutionStatus.COMPLETED
                || status == ToolReconciliationResolutionStatus.REUSED
                || status == ToolReconciliationResolutionStatus.IN_PROGRESS
                || status == ToolReconciliationResolutionStatus.SUPERSEDED;
        if (attemptRequired != attempt.isPresent()) {
            throw new IllegalArgumentException("Resolution attempt availability is inconsistent");
        }
    }
}
