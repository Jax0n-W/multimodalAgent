package com.multimodalAgent.agent.eval;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record EvalRunMetadata(
        String gitSha,
        EvalSourceTreeState sourceTreeState,
        String suiteId,
        String suiteVersion,
        Instant generatedAt,
        String target,
        List<String> runtimeConfigSnapshotIds,
        List<String> modelIdentities,
        boolean fullyReproducible,
        List<String> reproducibilityIssues
) {

    public EvalRunMetadata {
        Objects.requireNonNull(sourceTreeState, "sourceTreeState must not be null");
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        if (suiteId == null || suiteId.isBlank()) {
            throw new IllegalArgumentException("suiteId must not be blank");
        }
        if (suiteVersion == null || suiteVersion.isBlank()) {
            throw new IllegalArgumentException("suiteVersion must not be blank");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        runtimeConfigSnapshotIds = List.copyOf(Objects.requireNonNull(
                runtimeConfigSnapshotIds, "runtimeConfigSnapshotIds must not be null"
        ));
        modelIdentities = List.copyOf(Objects.requireNonNull(
                modelIdentities, "modelIdentities must not be null"
        ));
        if (runtimeConfigSnapshotIds.stream().anyMatch(
                value -> value == null || value.isBlank()
        )) {
            throw new IllegalArgumentException(
                    "runtimeConfigSnapshotIds must not contain blank values"
            );
        }
        if (modelIdentities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                    "modelIdentities must not contain blank values"
            );
        }
        reproducibilityIssues = List.copyOf(Objects.requireNonNull(
                reproducibilityIssues, "reproducibilityIssues must not be null"
        ));
        if (fullyReproducible && (
                gitSha == null
                        || gitSha.isBlank()
                        || sourceTreeState != EvalSourceTreeState.CLEAN
                        || runtimeConfigSnapshotIds.isEmpty()
                        || modelIdentities.isEmpty()
                        || !reproducibilityIssues.isEmpty()
        )) {
            throw new IllegalArgumentException(
                    "fullyReproducible requires clean, complete source and runtime provenance"
            );
        }
    }
}
