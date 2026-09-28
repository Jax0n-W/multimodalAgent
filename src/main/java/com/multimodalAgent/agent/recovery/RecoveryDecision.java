package com.multimodalAgent.agent.recovery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RecoveryDecision(
        String runId,
        RecoveryDisposition disposition,
        RecoveryReason primaryReason,
        Optional<String> checkpointId,
        Optional<Long> checkpointSequence,
        int modelFactCount,
        int toolFactCount,
        List<RecoveryToolDiagnostic> ambiguousTools,
        List<RecoveryEvidenceIssue> evidenceIssues
) {

    public RecoveryDecision {
        requireText(runId, "runId");
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(primaryReason, "primaryReason must not be null");
        Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        checkpointId.ifPresent(value -> requireText(value, "checkpointId"));
        Objects.requireNonNull(checkpointSequence, "checkpointSequence must not be null");
        if (modelFactCount < 0 || toolFactCount < 0) {
            throw new IllegalArgumentException("fact counts must not be negative");
        }
        ambiguousTools = List.copyOf(Objects.requireNonNull(
                ambiguousTools,
                "ambiguousTools must not be null"
        ));
        evidenceIssues = List.copyOf(Objects.requireNonNull(
                evidenceIssues,
                "evidenceIssues must not be null"
        ));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
