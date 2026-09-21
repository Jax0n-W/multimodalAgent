package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.bridge.ModelDeltaStreamBridge;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Outer production execution entry for P8.3 observation and P8.4 local control. P6 admission
 * runs before the request contributor opens the stream and registers control. This shell cleans
 * both up only after the complete P7/P6 execution returns or throws; cleanup never changes the
 * caller-visible outcome.
 */
public final class StreamingAgentExecutionService {

    private static final System.Logger LOGGER = System.getLogger(
            StreamingAgentExecutionService.class.getName()
    );

    private final Function<AgentExecutionRequest, AgentRunResult> execution;
    private final ExecutionStreamHub hub;
    private final ExecutionStreamPublisher publisher;
    private final LocalExecutionControlRegistry controls;

    public StreamingAgentExecutionService(
            Function<AgentExecutionRequest, AgentRunResult> execution,
            ExecutionStreamHub hub,
            ExecutionStreamPublisher publisher,
            LocalExecutionControlRegistry controls
    ) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String runId = request.runSpec().runId();
        LocalExecutionControlRegistry.Entry control = controls.create(runId);
        AtomicBoolean openedByThisExecution = new AtomicBoolean();
        AgentExecutionRequest observedRequest = request.withCancellationContext(control)
                .withRuntimeContextContributor(context -> {
            // The contributor is called after P6 durable admission and before RUN_STARTED.
            // A duplicate durable runId fails admission and cannot create another stream generation.
            hub.openRun(runId);
            openedByThisExecution.set(true);
            controls.register(control);
            StreamingModelInvocationScope.registerObserver(
                    context,
                    new ModelDeltaStreamBridge(runId, publisher)
            );
        });
        try {
            return execution.apply(observedRequest);
        } finally {
            controls.close(control);
            if (openedByThisExecution.get()) {
                try {
                    hub.closeRun(runId);
                } catch (RuntimeException exception) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "Execution stream cleanup failed for run {0}: {1}",
                            runId,
                            exception.getClass().getSimpleName()
                    );
                }
            }
        }
    }
}
