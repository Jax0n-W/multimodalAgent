package com.multimodalAgent.agent.execution.config;

public record ExecutionConfigSnapshot(
        String snapshotId,
        int schemaVersion,
        String configHash,
        String configJson
) {

    public ExecutionConfigSnapshot {
        requireText(snapshotId, "snapshotId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        requireText(configHash, "configHash");
        requireText(configJson, "configJson");
        if (!configHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("configHash must be a lowercase SHA-256 hex value");
        }
        String expectedSnapshotId = "exec-config-v" + schemaVersion + "-" + configHash;
        if (!expectedSnapshotId.equals(snapshotId)) {
            throw new IllegalArgumentException(
                    "snapshotId must be derived from schemaVersion and configHash"
            );
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
