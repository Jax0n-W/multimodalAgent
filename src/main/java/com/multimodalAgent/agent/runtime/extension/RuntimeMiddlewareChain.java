package com.multimodalAgent.agent.runtime.extension;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, reusable composition of transparent runtime middleware.
 * Middleware-origin failures are marked with {@link RuntimeMiddlewareFailureException}, while
 * downstream core failures are rethrown unchanged.
 */
public final class RuntimeMiddlewareChain {

    private static final RuntimeMiddlewareChain EMPTY = new RuntimeMiddlewareChain(List.of());

    private final List<RuntimeMiddleware> middleware;

    public RuntimeMiddlewareChain(Collection<? extends RuntimeMiddleware> middleware) {
        Objects.requireNonNull(middleware, "middleware must not be null");
        List<RuntimeMiddleware> ordered = new ArrayList<>(middleware.size());
        for (RuntimeMiddleware item : middleware) {
            ordered.add(Objects.requireNonNull(item, "middleware item must not be null"));
        }
        ordered.sort(Comparator.comparingInt(RuntimeMiddleware::order));
        this.middleware = List.copyOf(ordered);
    }

    public static RuntimeMiddlewareChain empty() {
        return EMPTY;
    }

    public AgentRunResult aroundRun(
            AgentRuntimeContext context,
            RuntimeInvocation<AgentRunResult> core
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(core, "core must not be null");
        return invokeRun(0, context, core);
    }

    public ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> core
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(core, "core must not be null");
        return invokeModel(0, context, metadata, core);
    }

    public ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> core
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(core, "core must not be null");
        return invokeTool(0, context, metadata, core);
    }

    private AgentRunResult invokeRun(
            int index,
            AgentRuntimeContext context,
            RuntimeInvocation<AgentRunResult> core
    ) {
        if (index == middleware.size()) {
            return core.proceed();
        }
        RuntimeMiddleware current = middleware.get(index);
        return invokeMiddleware(
                "aroundRun",
                next -> current.aroundRun(context, next),
                () -> invokeRun(index + 1, context, core)
        );
    }

    private ModelTurn invokeModel(
            int index,
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> core
    ) {
        if (index == middleware.size()) {
            return core.proceed();
        }
        RuntimeMiddleware current = middleware.get(index);
        return invokeMiddleware(
                "aroundModelCall",
                next -> current.aroundModelCall(context, metadata, next),
                () -> invokeModel(index + 1, context, metadata, core)
        );
    }

    private ToolResult invokeTool(
            int index,
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> core
    ) {
        if (index == middleware.size()) {
            return core.proceed();
        }
        RuntimeMiddleware current = middleware.get(index);
        return invokeMiddleware(
                "aroundToolExecution",
                next -> current.aroundToolExecution(context, metadata, next),
                () -> invokeTool(index + 1, context, metadata, core)
        );
    }

    private <T> T invokeMiddleware(
            String operationName,
            MiddlewareOperation<T> operation,
            RuntimeInvocation<T> downstream
    ) {
        ProceedOnceGuard<T> guard = new ProceedOnceGuard<>(downstream);
        try {
            T returned = operation.invoke(guard::proceed);
            guard.verifyReturn(returned);
            return returned;
        } catch (DownstreamInvocationException exception) {
            throw exception.downstreamFailure();
        } catch (RuntimeException exception) {
            if (guard.downstreamFailure() != null) {
                throw guard.downstreamFailure();
            }
            if (exception instanceof RuntimeMiddlewareFailureException middlewareFailure) {
                throw middlewareFailure;
            }
            throw new RuntimeMiddlewareFailureException(
                    operationName + " middleware failed",
                    exception
            );
        }
    }

    @FunctionalInterface
    private interface MiddlewareOperation<T> {

        T invoke(RuntimeInvocation<T> next);
    }

    private static final class ProceedOnceGuard<T> {

        private final RuntimeInvocation<T> downstream;
        private int invocationCount;
        private T downstreamResult;
        private RuntimeException downstreamFailure;

        private ProceedOnceGuard(RuntimeInvocation<T> downstream) {
            this.downstream = downstream;
        }

        private synchronized T proceed() {
            invocationCount++;
            if (invocationCount != 1) {
                throw new RuntimeMiddlewareFailureException(
                        "next.proceed() must be called exactly once"
                );
            }
            try {
                downstreamResult = downstream.proceed();
                return downstreamResult;
            } catch (RuntimeException exception) {
                downstreamFailure = exception;
                throw new DownstreamInvocationException(exception);
            }
        }

        private synchronized void verifyReturn(T returned) {
            if (downstreamFailure != null) {
                throw new DownstreamInvocationException(downstreamFailure);
            }
            if (invocationCount != 1) {
                throw new RuntimeMiddlewareFailureException(
                        "next.proceed() must be called exactly once"
                );
            }
            if (returned != downstreamResult) {
                throw new RuntimeMiddlewareFailureException(
                        "middleware must return the downstream result"
                );
            }
        }

        private synchronized RuntimeException downstreamFailure() {
            return downstreamFailure;
        }
    }

    private static final class DownstreamInvocationException extends RuntimeException {

        private final RuntimeException downstreamFailure;

        private DownstreamInvocationException(RuntimeException downstreamFailure) {
            super(null, downstreamFailure, false, false);
            this.downstreamFailure = downstreamFailure;
        }

        private RuntimeException downstreamFailure() {
            return downstreamFailure;
        }
    }
}
