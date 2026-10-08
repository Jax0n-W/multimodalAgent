package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.recovery.RecoveryExecutionLifecycle;
import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;

/** Adds the shared P8 observation/control segment around P10 existing-run resume. */
public final class RecoveryStreamingExecutionLifecycle
        implements RecoveryExecutionLifecycle {

    private final StreamingRunExecutionLifecycle lifecycle;
    private final PersistentAgentExecutionCoordinator persistence;

    public RecoveryStreamingExecutionLifecycle(
            StreamingRunExecutionLifecycle lifecycle,
            PersistentAgentExecutionCoordinator persistence
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.persistence = Objects.requireNonNull(
                persistence,
                "persistence must not be null"
        );
    }

    @Override
    public AgentRunResult resume(RecoveryExecutionRequest request) {
        return lifecycle.executeRecovery(request, persistence::resumeExisting);
    }
}
