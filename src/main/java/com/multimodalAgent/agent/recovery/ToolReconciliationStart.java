package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolReconciliationStart(
        ToolReconciliationStartDisposition disposition,
        Optional<ToolReconciliationAttempt> attempt
) {

    public ToolReconciliationStart {
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        if ((disposition == ToolReconciliationStartDisposition.STARTED
                || disposition == ToolReconciliationStartDisposition.REUSED_DECISIVE
                || disposition == ToolReconciliationStartDisposition.ALREADY_IN_PROGRESS)
                != attempt.isPresent()) {
            throw new IllegalArgumentException("Reconciliation start disposition is inconsistent");
        }
    }

    public static ToolReconciliationStart withoutAttempt(
            ToolReconciliationStartDisposition disposition
    ) {
        return new ToolReconciliationStart(disposition, Optional.empty());
    }
}
