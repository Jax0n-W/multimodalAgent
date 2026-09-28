package com.multimodalAgent.agent.recovery;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RecoveryEvidence(
        String runId,
        Optional<RecoveryRunEvidence> run,
        Optional<RecoveryCheckpoint> latestCheckpoint,
        List<RecoveryModelEvidence> modelFacts,
        List<RecoveryToolEvidence> toolFacts,
        List<RecoveryEvidenceIssue> issues
) {

    public RecoveryEvidence {
        requireText(runId, "runId");
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(latestCheckpoint, "latestCheckpoint must not be null");
        modelFacts = List.copyOf(Objects.requireNonNull(modelFacts, "modelFacts must not be null"));
        toolFacts = List.copyOf(Objects.requireNonNull(toolFacts, "toolFacts must not be null"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
    }

    public static RecoveryEvidence missingRun(String runId) {
        return new RecoveryEvidence(
                runId,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
