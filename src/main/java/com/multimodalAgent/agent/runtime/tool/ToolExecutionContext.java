package com.multimodalAgent.agent.runtime.tool;

import java.util.Objects;
import java.util.Optional;

/** Immutable, runtime-owned identity passed to a tool after policy and schema validation. */
public record ToolExecutionContext(
        String runId,
        String toolCallId,
        String sessionId,
        Optional<Long> userId,
        Optional<String> runtimeConfigSnapshotId
) {

    public ToolExecutionContext {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(
                runtimeConfigSnapshotId,
                "runtimeConfigSnapshotId must not be null"
        );
        runtimeConfigSnapshotId.ifPresent(value -> requireText(
                value,
                "runtimeConfigSnapshotId"
        ));
    }

    public long requireUserId() {
        return userId.orElseThrow(() -> new IllegalStateException(
                "Authenticated user identity is required for this tool"
        ));
    }

    public String requireRuntimeConfigSnapshotId() {
        return runtimeConfigSnapshotId.orElseThrow(() -> new IllegalStateException(
                "Immutable runtime configuration snapshot is required for this tool"
        ));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
