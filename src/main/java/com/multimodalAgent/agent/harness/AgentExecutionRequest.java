package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

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
        String contextSnapshotId,
        CancellationContext cancellationContext,
        List<AgentRuntimeContextContributor> runtimeContextContributors,
        List<AgentMessage> trustedBusinessContext
) {

    public AgentExecutionRequest {
        Objects.requireNonNull(runSpec, "runSpec must not be null");
        requireOptionalText(requestId, "requestId");
        requireOptionalText(runtimeConfigSnapshotId, "runtimeConfigSnapshotId");
        requireOptionalText(contextSnapshotId, "contextSnapshotId");
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
        trustedBusinessContext = List.copyOf(Objects.requireNonNull(
                trustedBusinessContext,
                "trustedBusinessContext must not be null"
        ));
    }

    public AgentExecutionRequest(
            AgentRunSpec runSpec,
            String requestId,
            Long userId,
            String runtimeConfigSnapshotId,
            String contextSnapshotId,
            CancellationContext cancellationContext,
            List<AgentRuntimeContextContributor> runtimeContextContributors
    ) {
        this(
                runSpec, requestId, userId, runtimeConfigSnapshotId, contextSnapshotId,
                cancellationContext, runtimeContextContributors, List.of()
        );
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
                null,
                cancellationContext,
                List.of(),
                List.of()
        );
    }

    public AgentExecutionRequest(
            AgentRunSpec runSpec,
            String requestId,
            Long userId,
            String runtimeConfigSnapshotId,
            CancellationContext cancellationContext,
            List<AgentRuntimeContextContributor> runtimeContextContributors
    ) {
        this(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                null,
                cancellationContext,
                runtimeContextContributors,
                List.of()
        );
    }

    public AgentExecutionRequest(
            AgentRunSpec runSpec,
            String requestId,
            Long userId,
            String runtimeConfigSnapshotId,
            String contextSnapshotId,
            CancellationContext cancellationContext
    ) {
        this(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                contextSnapshotId,
                cancellationContext,
                List.of(),
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
                contextSnapshotId,
                cancellationContext,
                contributors,
                trustedBusinessContext
        );
    }

    public AgentExecutionRequest withCancellationContext(CancellationContext context) {
        return new AgentExecutionRequest(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                contextSnapshotId,
                Objects.requireNonNull(context, "context must not be null"),
                runtimeContextContributors,
                trustedBusinessContext
        );
    }

    public AgentExecutionRequest withRuntimeConfigSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return new AgentExecutionRequest(
                runSpec,
                requestId,
                userId,
                snapshotId,
                contextSnapshotId,
                cancellationContext,
                runtimeContextContributors,
                trustedBusinessContext
        );
    }

    public AgentExecutionRequest withContextSnapshotId(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return new AgentExecutionRequest(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                snapshotId,
                cancellationContext,
                runtimeContextContributors,
                trustedBusinessContext
        );
    }

    public AgentExecutionRequest withRunSpec(AgentRunSpec replacement) {
        return new AgentExecutionRequest(
                Objects.requireNonNull(replacement, "replacement must not be null"),
                requestId,
                userId,
                runtimeConfigSnapshotId,
                contextSnapshotId,
                cancellationContext,
                runtimeContextContributors,
                trustedBusinessContext
        );
    }

    public AgentExecutionRequest withTrustedBusinessContext(List<AgentMessage> messages) {
        return new AgentExecutionRequest(
                runSpec,
                requestId,
                userId,
                runtimeConfigSnapshotId,
                contextSnapshotId,
                cancellationContext,
                runtimeContextContributors,
                messages
        );
    }

    private static void requireOptionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when present");
        }
    }
}
