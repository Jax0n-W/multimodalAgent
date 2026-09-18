package com.multimodalAgent.agent.runtime.control;

import com.multimodalAgent.agent.runtime.extension.CancellationContext;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Provider- and infrastructure-neutral cancellation intent for one execution.
 *
 * <p>Requesting cancellation is idempotent and monotonic. It does not interrupt a thread, cancel
 * an HTTP request, terminate an in-flight Model or Tool operation, or mutate durable state.</p>
 */
public interface ExecutionControl extends CancellationContext {

    static ExecutionControl create() {
        return new AtomicExecutionControl();
    }

    ExecutionControlState state();

    CancelRequestResult requestCancel();

    @Override
    default boolean isCancellationRequested() {
        return state() == ExecutionControlState.CANCEL_REQUESTED;
    }
}

final class AtomicExecutionControl implements ExecutionControl {

    private final AtomicReference<ExecutionControlState> state =
            new AtomicReference<>(ExecutionControlState.RUNNING);

    @Override
    public ExecutionControlState state() {
        return state.get();
    }

    @Override
    public CancelRequestResult requestCancel() {
        return state.compareAndSet(
                ExecutionControlState.RUNNING,
                ExecutionControlState.CANCEL_REQUESTED
        ) ? CancelRequestResult.ACCEPTED : CancelRequestResult.ALREADY_REQUESTED;
    }
}
