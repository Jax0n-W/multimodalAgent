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
 * Outer production execution entry for P8.3 observation. P6 admission runs before the request
 * contributor opens the live stream, and this shell closes it only after the complete P7/P6
 * execution returns or throws. Stream cleanup never changes the caller-visible outcome.
 */
public final class StreamingAgentExecutionService {

    private static final System.Logger LOGGER = System.getLogger(
            StreamingAgentExecutionService.class.getName()
    );

    private final Function<AgentExecutionRequest, AgentRunResult> execution;
    private final ExecutionStreamHub hub;
    private final ExecutionStreamPublisher publisher;

    public StreamingAgentExecutionService(
            Function<AgentExecutionRequest, AgentRunResult> execution,
            ExecutionStreamHub hub,
            ExecutionStreamPublisher publisher
    ) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String runId = request.runSpec().runId();
        AtomicBoolean openedByThisExecution = new AtomicBoolean();
        AgentExecutionRequest observedRequest = request.withRuntimeContextContributor(context -> {
            // The contributor is called after P6 durable admission and before RUN_STARTED.
            // A duplicate durable runId fails admission and cannot create another stream generation.
            hub.openRun(runId);
            openedByThisExecution.set(true);
            StreamingModelInvocationScope.registerObserver(
                    context,
                    new ModelDeltaStreamBridge(runId, publisher)
            );
        });
        try {
            return execution.apply(observedRequest);
        } finally {
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
