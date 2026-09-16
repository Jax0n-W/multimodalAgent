package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.Objects;

/**
 * Fails future execution before dispatch, or after a currently started operation reaches its
 * terminal Core fact. The original persistence exception is rethrown by the outer coordinator.
 */
public final class ExecutionPersistenceBoundaryMiddleware implements RuntimeMiddleware {

    public static final int ORDER = 200;

    private final ExecutionPersistenceFailureRegistry failures;

    ExecutionPersistenceBoundaryMiddleware(ExecutionPersistenceFailureRegistry failures) {
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> next
    ) {
        failures.throwIfFailed(context.runId());
        ModelTurn turn = next.proceed();
        failures.throwIfFailed(context.runId());
        return turn;
    }

    @Override
    public ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> next
    ) {
        failures.throwIfFailed(context.runId());
        ToolResult result = next.proceed();
        failures.throwIfFailed(context.runId());
        return result;
    }
}
