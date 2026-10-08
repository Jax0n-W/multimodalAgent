package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.harness.AgentRuntimeContextContributor;
import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.bridge.ModelDeltaStreamBridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Shared node-local observation and cancellation lifecycle for one active execution segment. */
public final class StreamingRunExecutionLifecycle {

    private static final System.Logger LOGGER = System.getLogger(
            StreamingRunExecutionLifecycle.class.getName()
    );

    private final ExecutionStreamHub hub;
    private final ExecutionStreamPublisher publisher;
    private final LocalExecutionControlRegistry controls;

    public StreamingRunExecutionLifecycle(
            ExecutionStreamHub hub,
            ExecutionStreamPublisher publisher,
            LocalExecutionControlRegistry controls
    ) {
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
    }

    public AgentRunResult executeFresh(
            AgentExecutionRequest request,
            Function<AgentExecutionRequest, AgentRunResult> execution
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        String runId = request.runSpec().runId();
        LocalExecutionControlRegistry.Entry control = controls.create(runId);
        AtomicBoolean openedByThisSegment = new AtomicBoolean();
        AgentExecutionRequest observed = request.withCancellationContext(control)
                .withRuntimeContextContributor(initializer(
                        runId, control, openedByThisSegment
                ));
        return invoke(
                runId,
                control,
                openedByThisSegment,
                () -> execution.apply(observed)
        );
    }

    public AgentRunResult executeRecovery(
            RecoveryExecutionRequest request,
            Function<RecoveryExecutionRequest, AgentRunResult> execution
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        String runId = request.runSpec().runId();
        LocalExecutionControlRegistry.Entry control = controls.create(runId);
        AtomicBoolean openedByThisSegment = new AtomicBoolean();
        RecoveryExecutionRequest observed = request.withCancellationContext(control)
                .withRuntimeContextContributor(initializer(
                        runId, control, openedByThisSegment
                ));
        return invoke(
                runId,
                control,
                openedByThisSegment,
                () -> execution.apply(observed)
        );
    }

    private AgentRuntimeContextContributor initializer(
            String runId,
            LocalExecutionControlRegistry.Entry control,
            AtomicBoolean openedByThisSegment
    ) {
        return context -> {
            hub.openRun(runId);
            openedByThisSegment.set(true);
            controls.register(control);
            StreamingModelInvocationScope.registerObserver(
                    context,
                    new ModelDeltaStreamBridge(runId, publisher)
            );
        };
    }

    private AgentRunResult invoke(
            String runId,
            LocalExecutionControlRegistry.Entry control,
            AtomicBoolean openedByThisSegment,
            java.util.function.Supplier<AgentRunResult> invocation
    ) {
        Throwable primary = null;
        try {
            return invocation.get();
        } catch (RuntimeException | Error exception) {
            primary = exception;
            throw exception;
        } finally {
            cleanupControl(runId, control, primary);
            if (openedByThisSegment.get()) {
                cleanupStream(runId, primary);
            }
        }
    }

    private void cleanupControl(
            String runId,
            LocalExecutionControlRegistry.Entry control,
            Throwable primary
    ) {
        try {
            controls.close(control);
        } catch (RuntimeException exception) {
            recordCleanupFailure(runId, "control", primary, exception);
        }
    }

    private void cleanupStream(String runId, Throwable primary) {
        try {
            hub.closeRun(runId);
        } catch (RuntimeException exception) {
            recordCleanupFailure(runId, "stream", primary, exception);
        }
    }

    private void recordCleanupFailure(
            String runId,
            String resource,
            Throwable primary,
            RuntimeException cleanup
    ) {
        if (primary != null) {
            primary.addSuppressed(cleanup);
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Execution {0} cleanup failed for run {1}: {2}",
                resource,
                runId,
                cleanup.getClass().getSimpleName()
        );
    }
}
