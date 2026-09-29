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
        long startedAttemptCount,
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
                minimumStartedAttemptCount(status),
                Optional.empty()
        );
    }

    public RecoveryToolEvidence(
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
        this(
                executionId,
                stepId,
                iteration,
                stepIndex,
                toolCallId,
                toolName,
                status,
                linkedStepConsistent,
                minimumStartedAttemptCount(status),
                recoveryContract
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
        if (startedAttemptCount < 0) {
            throw new IllegalArgumentException("startedAttemptCount must not be negative");
        }
        Objects.requireNonNull(recoveryContract, "recoveryContract must not be null");
        if (recoveryContract.isPresent()
                && !toolName.equals(recoveryContract.orElseThrow().toolName())) {
            throw new IllegalArgumentException(
                    "Tool evidence identity must match recovery contract"
            );
        }
    }

    private static long minimumStartedAttemptCount(RecoveryToolStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        return status == RecoveryToolStatus.STARTED
                || status == RecoveryToolStatus.UNKNOWN
                || status == RecoveryToolStatus.SUCCEEDED
                || status == RecoveryToolStatus.FAILED ? 1L : 0L;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
