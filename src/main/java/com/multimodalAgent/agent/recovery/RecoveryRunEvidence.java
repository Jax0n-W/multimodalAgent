package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record RecoveryRunEvidence(
        String runId,
        RecoveryRunStatus status,
        int currentIteration,
        Optional<String> runtimeConfigSnapshotId
) {

    public RecoveryRunEvidence {
        requireText(runId, "runId");
        Objects.requireNonNull(status, "status must not be null");
        if (currentIteration < 0) {
            throw new IllegalArgumentException("currentIteration must not be negative");
        }
        Objects.requireNonNull(
                runtimeConfigSnapshotId,
                "runtimeConfigSnapshotId must not be null"
        );
        runtimeConfigSnapshotId.ifPresent(value -> requireText(
                value,
                "runtimeConfigSnapshotId"
        ));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
