package com.multimodalAgent.agent.recovery;

import java.util.Objects;

public record RecoveryModelEvidence(
        String stepId,
        int iteration,
        int stepIndex,
        RecoveryModelStatus status
) {

    public RecoveryModelEvidence {
        requireText(stepId, "stepId");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (stepIndex < 1) {
            throw new IllegalArgumentException("stepIndex must be at least 1");
        }
        Objects.requireNonNull(status, "status must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
