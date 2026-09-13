package com.multimodalAgent.agent.runtime.extension;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

/**
 * Transparent, thread-safe extension around runtime operations.
 * Implementations must call {@code next.proceed()} exactly once and return that same result.
 * Per-run mutable state belongs in {@link AgentRuntimeContext#attributes()}.
 */
public interface RuntimeMiddleware {

    default int order() {
        return 0;
    }

    default AgentRunResult aroundRun(
            AgentRuntimeContext context,
            RuntimeInvocation<AgentRunResult> next
    ) {
        return next.proceed();
    }

    default ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> next
    ) {
        return next.proceed();
    }

    default ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> next
    ) {
        return next.proceed();
    }
}
