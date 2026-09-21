package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributeKey;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.stream.ModelDeltaObserver;

import java.util.Objects;

/**
 * Captures the current Runtime iteration and run-owned delta observer without changing the
 * AgentModel request contract.
 *
 * <p>The adapter reads the immutable invocation observation synchronously before subscribing to
 * provider I/O. Reactor callbacks use that captured value rather than consulting this
 * caller-thread scope. This middleware remains transparent and returns the exact downstream
 * ModelTurn.</p>
 */
public final class StreamingModelInvocationScope implements RuntimeMiddleware {

    private static final RuntimeAttributeKey<ModelDeltaObserver> MODEL_DELTA_OBSERVER =
            RuntimeAttributeKey.of(
                    "streaming-model-delta-observer",
                    ModelDeltaObserver.class
            );

    private final ThreadLocal<InvocationObservation> currentObservation = new ThreadLocal<>();

    /** Registers the observer owned by one run context. */
    public static void registerObserver(
            AgentRuntimeContext context,
            ModelDeltaObserver observer
    ) {
        Objects.requireNonNull(context, "context must not be null")
                .attributes()
                .put(MODEL_DELTA_OBSERVER, Objects.requireNonNull(
                        observer,
                        "observer must not be null"
                ));
    }

    @Override
    public ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> next
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(next, "next must not be null");
        InvocationObservation previous = currentObservation.get();
        ModelDeltaObserver observer = context.attributes()
                .get(MODEL_DELTA_OBSERVER)
                .orElse(ModelDeltaObserver.NOOP);
        currentObservation.set(new InvocationObservation(metadata.iteration(), observer));
        try {
            return next.proceed();
        } finally {
            if (previous == null) {
                currentObservation.remove();
            } else {
                currentObservation.set(previous);
            }
        }
    }

    /**
     * Captures invocation state on the caller thread before provider subscription begins.
     * Reactor callbacks must retain this returned value and never consult the ThreadLocal.
     */
    InvocationObservation capture() {
        InvocationObservation observation = currentObservation.get();
        return observation == null ? InvocationObservation.disabled() : observation;
    }

    record InvocationObservation(int iteration, ModelDeltaObserver observer) {

        InvocationObservation {
            if (iteration < 0) {
                throw new IllegalArgumentException("iteration must not be negative");
            }
            Objects.requireNonNull(observer, "observer must not be null");
        }

        static InvocationObservation disabled() {
            return new InvocationObservation(0, ModelDeltaObserver.NOOP);
        }

        boolean enabled() {
            return iteration > 0 && observer != ModelDeltaObserver.NOOP;
        }
    }
}
