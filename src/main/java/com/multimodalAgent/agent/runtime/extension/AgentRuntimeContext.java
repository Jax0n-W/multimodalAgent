package com.multimodalAgent.agent.runtime.extension;

import java.util.Objects;

public record AgentRuntimeContext(
        String runId,
        String requestId,
        String sessionId,
        Long userId,
        String runtimeConfigSnapshotId,
        CancellationContext cancellationContext,
        RuntimeAttributes attributes
) {

    public AgentRuntimeContext {
        requireText(runId, "runId");
        requireText(sessionId, "sessionId");
        requireOptionalText(requestId, "requestId");
        requireOptionalText(runtimeConfigSnapshotId, "runtimeConfigSnapshotId");
        Objects.requireNonNull(cancellationContext, "cancellationContext must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
    }

    public static AgentRuntimeContext minimal(String runId, String sessionId) {
        return new AgentRuntimeContext(
                runId,
                null,
                sessionId,
                null,
                null,
                CancellationContext.NONE,
                new RuntimeAttributes()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
    }
}
