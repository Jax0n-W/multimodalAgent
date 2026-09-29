package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolRecoveryAssessment(
        String toolCallId,
        String toolName,
        RecoveryToolStatus durableStatus,
        boolean contractAvailable,
        Optional<String> contractId,
        Optional<String> contractVersion,
        Optional<ToolReplaySemantics> replaySemantics,
        Optional<ToolReconciliationSupport> reconciliationSupport,
        Optional<String> reconciliationStrategyId,
        Optional<ToolRecoveryClass> recoveryClass,
        ToolRecoveryAssessmentReason reason
) {

    public ToolRecoveryAssessment {
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(durableStatus, "durableStatus must not be null");
        Objects.requireNonNull(contractId, "contractId must not be null");
        Objects.requireNonNull(contractVersion, "contractVersion must not be null");
        Objects.requireNonNull(replaySemantics, "replaySemantics must not be null");
        Objects.requireNonNull(
                reconciliationSupport,
                "reconciliationSupport must not be null"
        );
        Objects.requireNonNull(
                reconciliationStrategyId,
                "reconciliationStrategyId must not be null"
        );
        Objects.requireNonNull(recoveryClass, "recoveryClass must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        boolean complete = contractId.isPresent()
                && contractVersion.isPresent()
                && replaySemantics.isPresent()
                && reconciliationSupport.isPresent()
                && recoveryClass.isPresent();
        if (contractAvailable != complete) {
            throw new IllegalArgumentException("Contract assessment availability is inconsistent");
        }
        if (contractAvailable != (reason == ToolRecoveryAssessmentReason.CONTRACT_AVAILABLE)) {
            throw new IllegalArgumentException("Contract assessment reason is inconsistent");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
