package com.multimodalAgent.agent.runtime.extension;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

/**
 * Transparent, thread-safe extension around runtime operations.
 *
 * <p>Implementations must call {@code next.proceed()} exactly once and return the exact result
 * instance produced by downstream execution. Middleware must not short-circuit, replace results,
 * retain or transfer {@code next}, or change core execution semantics. The {@code next} capability
 * is valid only in the dynamic scope and on the thread of its middleware invocation. It is intended
 * for concerns such as metrics, tracing, logging, latency or token observation, and runtime-context
 * enrichment.</p>
 *
 * <p>Request rejection, idempotency, locking, admission control, rate limiting, cancellation
 * prechecks, and cached-result short-circuiting belong to a future execution guard/preflight
 * boundary, not this middleware API. Implementations may be shared between runs and must therefore
 * be stateless or thread-safe. Per-run mutable state belongs in
 * {@link AgentRuntimeContext#attributes()}.</p>
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
