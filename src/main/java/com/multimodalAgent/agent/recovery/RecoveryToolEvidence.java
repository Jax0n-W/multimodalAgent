package com.multimodalAgent.agent.recovery;

import java.util.Objects;

public record RecoveryToolEvidence(
        String executionId,
        String stepId,
        int iteration,
        int stepIndex,
        String toolCallId,
        String toolName,
        RecoveryToolStatus status,
        boolean linkedStepConsistent
) {

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
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
