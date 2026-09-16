package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;

import java.util.List;
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
        CancellationContext cancellationContext,
        List<AgentRuntimeContextContributor> runtimeContextContributors
) {

    public AgentExecutionRequest {
        Objects.requireNonNull(runSpec, "runSpec must not be null");
        requireOptionalText(requestId, "requestId");
        requireOptionalText(runtimeConfigSnapshotId, "runtimeConfigSnapshotId");
        Objects.requireNonNull(cancellationContext, "cancellationContext must not be null");
        Objects.requireNonNull(
                runtimeContextContributors,
                "runtimeContextContributors must not be null"
        );
        for (AgentRuntimeContextContributor contributor : runtimeContextContributors) {
            Objects.requireNonNull(
                    contributor,
                    "runtimeContextContributors must not contain null"
            );
        }
        runtimeContextContributors = List.copyOf(runtimeContextContributors);
    }

    public AgentExecutionRequest(
            AgentRunSpec runSpec,
            String requestId,
            Long userId,
            String runtimeConfigSnapshotId,
            CancellationContext cancellationContext
    ) {
        this(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                cancellationContext,
                List.of()
        );
    }

    public AgentExecutionRequest(AgentRunSpec runSpec, String requestId, Long userId) {
        this(runSpec, requestId, userId, null, CancellationContext.NONE);
    }

    public AgentExecutionRequest withRuntimeContextContributor(
            AgentRuntimeContextContributor contributor
    ) {
        Objects.requireNonNull(contributor, "contributor must not be null");
        List<AgentRuntimeContextContributor> contributors =
                new java.util.ArrayList<>(runtimeContextContributors);
        contributors.add(contributor);
        return new AgentExecutionRequest(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                cancellationContext,
                contributors
        );
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
    }
}
