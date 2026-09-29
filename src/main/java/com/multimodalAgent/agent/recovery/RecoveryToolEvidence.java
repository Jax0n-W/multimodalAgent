package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record RecoveryToolEvidence(
        String executionId,
        String stepId,
        int iteration,
        int stepIndex,
        String toolCallId,
        String toolName,
        RecoveryToolStatus status,
        boolean linkedStepConsistent,
        Optional<ToolRecoveryContractSnapshot> recoveryContract
) {

    public RecoveryToolEvidence(
            String executionId,
            String stepId,
            int iteration,
            int stepIndex,
            String toolCallId,
            String toolName,
            RecoveryToolStatus status,
            boolean linkedStepConsistent
    ) {
        this(
                executionId,
                stepId,
                iteration,
                stepIndex,
                toolCallId,
                toolName,
                status,
                linkedStepConsistent,
                Optional.empty()
        );
    }

    public RecoveryToolEvidence {
        requireText(executionId, "executionId");
        requireText(stepId, "stepId");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must not be negative");
        }
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(recoveryContract, "recoveryContract must not be null");
        if (recoveryContract.isPresent()
                && !toolName.equals(recoveryContract.orElseThrow().toolName())) {
            throw new IllegalArgumentException(
                    "Tool evidence identity must match recovery contract"
            );
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
