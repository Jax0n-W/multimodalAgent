package com.multimodalAgent.agent.execution.config;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;
import java.util.function.Function;

/** Outer execution guard that persists resolved config before P7/P6 execution begins. */
public final class SnapshottingAgentExecutionCoordinator {

    private final ResolvedExecutionConfigResolver resolver;
    private final ExecutionConfigSnapshotFactory snapshotFactory;
    private final ExecutionConfigSnapshotStore store;
    private final Function<AgentExecutionRequest, AgentRunResult> delegate;

    public SnapshottingAgentExecutionCoordinator(
            ResolvedExecutionConfigResolver resolver,
            ExecutionConfigSnapshotFactory snapshotFactory,
            ExecutionConfigSnapshotStore store,
            Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.snapshotFactory = Objects.requireNonNull(
                snapshotFactory, "snapshotFactory must not be null"
        );
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ResolvedExecutionConfig resolved = resolver.resolve(request);
        ExecutionConfigSnapshot requested = snapshotFactory.create(resolved);
        ExecutionConfigSnapshot stored = store.persistIfAbsent(requested);
        if (!requested.equals(stored)) {
            throw new ExecutionConfigSnapshotException(
                    "Snapshot store returned content that differs from the resolved configuration"
            );
        }
        return delegate.apply(request.withRuntimeConfigSnapshotId(stored.snapshotId()));
    }
}
