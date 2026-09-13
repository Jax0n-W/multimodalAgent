package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;

import java.util.Objects;

/**
 * Application-level input for one coordinated Agent execution.
 * A request ID may be absent for minimal and test paths; a future production idempotency guard
 * will own the requirement that production executions provide one.
 */
public record AgentExecutionRequest(
        AgentRunSpec runSpec,
        String requestId,
        Long userId,
        String runtimeConfigSnapshotId,
        CancellationContext cancellationContext
) {

    public AgentExecutionRequest {
        Objects.requireNonNull(runSpec, "runSpec must not be null");
        requireOptionalText(requestId, "requestId");
        requireOptionalText(runtimeConfigSnapshotId, "runtimeConfigSnapshotId");
        Objects.requireNonNull(cancellationContext, "cancellationContext must not be null");
    }

    public AgentExecutionRequest(AgentRunSpec runSpec, String requestId, Long userId) {
        this(runSpec, requestId, userId, null, CancellationContext.NONE);
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
    }
}
