package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;
import java.util.function.Function;

/** Fresh-execution guard that durably fixes semantic context before config snapshotting. */
public final class ContextAssemblingAgentExecutionCoordinator {

    private final AgentContextAssembler assembler;
    private final AgentContextSnapshotStore store;
    private final Function<AgentExecutionRequest, AgentRunResult> delegate;

    public ContextAssemblingAgentExecutionCoordinator(
            AgentContextAssembler assembler,
            AgentContextSnapshotStore store,
            Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        this.assembler = Objects.requireNonNull(assembler, "assembler must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentContextSnapshot requested = assembler.assemble(new ContextAssemblyInput(
                request.runSpec().runId(),
                request.runSpec().sessionId(),
                request.userId(),
                request.runSpec().messages(),
                request.runSpec().allowedTools()
        ));
        AgentContextSnapshot stored = store.persistIfAbsent(requested);
        if (!requested.sameSemanticContent(stored)) {
            throw new ContextAssemblyException(
                    "Context snapshot store returned content different from assembled context"
            );
        }
        return delegate.apply(
                request.withRunSpec(request.runSpec().withMessages(requested.messages()))
                        .withContextSnapshotId(stored.snapshotId())
        );
    }
}
