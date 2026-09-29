package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentResumeState;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;

import java.util.List;
import java.util.Objects;

/** Existing-run input. It deliberately has no fresh request/admission semantics. */
public record RecoveryExecutionRequest(
        AgentRunSpec runSpec,
        AgentResumeState resumeState,
        String runtimeConfigSnapshotId,
        CancellationContext cancellationContext,
        List<AgentRuntimeContextContributor> runtimeContextContributors
) {
    public RecoveryExecutionRequest {
        Objects.requireNonNull(runSpec, "runSpec must not be null");
        Objects.requireNonNull(resumeState, "resumeState must not be null");
        if (runtimeConfigSnapshotId == null || runtimeConfigSnapshotId.isBlank()) {
            throw new IllegalArgumentException("runtimeConfigSnapshotId must not be blank");
        }
        Objects.requireNonNull(cancellationContext, "cancellationContext must not be null");
        runtimeContextContributors = List.copyOf(Objects.requireNonNull(
                runtimeContextContributors, "runtimeContextContributors must not be null"
        ));
    }

    public RecoveryExecutionRequest withRuntimeContextContributor(
            AgentRuntimeContextContributor contributor
    ) {
        var contributors = new java.util.ArrayList<>(runtimeContextContributors);
        contributors.add(Objects.requireNonNull(contributor, "contributor must not be null"));
        return new RecoveryExecutionRequest(
                runSpec, resumeState, runtimeConfigSnapshotId,
                cancellationContext, contributors
        );
    }
}
