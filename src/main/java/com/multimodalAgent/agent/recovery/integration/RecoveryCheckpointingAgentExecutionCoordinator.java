package com.multimodalAgent.agent.recovery.integration;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.harness.AgentRuntimeContextContributor;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Installs one isolated checkpoint state mirror after P9.3 has resolved the snapshot identity. */
public final class RecoveryCheckpointingAgentExecutionCoordinator {

    private final RecoveryCheckpointStore store;
    private final Optional<ModelIdentity> modelIdentity;
    private final Function<AgentExecutionRequest, AgentRunResult> delegate;
    private final Clock clock;

    public RecoveryCheckpointingAgentExecutionCoordinator(
            RecoveryCheckpointStore store,
            Optional<ModelIdentity> modelIdentity,
            Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        this(store, modelIdentity, delegate, Clock.systemUTC());
    }

    RecoveryCheckpointingAgentExecutionCoordinator(
            RecoveryCheckpointStore store,
            Optional<ModelIdentity> modelIdentity,
            Function<AgentExecutionRequest, AgentRunResult> delegate,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String snapshotId = request.runtimeConfigSnapshotId();
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalStateException(
                    "Durable recovery checkpointing requires the original runtime config snapshot"
            );
        }
        RecoveryCheckpointSession session = new RecoveryCheckpointSession(
                request.runSpec(), snapshotId, modelIdentity, store, clock
        );
        return delegate.apply(request.withRuntimeContextContributor(
                context -> context.attributes().put(
                        RecoveryCheckpointRuntimeMiddleware.SESSION,
                        session
                )
        ));
    }

    public static AgentRuntimeContextContributor resumeSessionContributor(
            AgentRunSpec spec,
            RecoveryCheckpoint checkpoint,
            BudgetUsage recoveredUsage,
            Optional<ModelIdentity> modelIdentity,
            RecoveryCheckpointStore store
    ) {
        RecoveryCheckpointSession session = new RecoveryCheckpointSession(
                spec, checkpoint, recoveredUsage, modelIdentity, store, Clock.systemUTC()
        );
        return context -> context.attributes().put(
                RecoveryCheckpointRuntimeMiddleware.SESSION, session
        );
    }
}
