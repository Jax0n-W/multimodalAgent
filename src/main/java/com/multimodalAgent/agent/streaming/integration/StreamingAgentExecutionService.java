package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;

/**
 * Outer production execution entry for P8.3 observation and P8.4 local control. P6 admission
 * runs before the request contributor opens the stream and registers control. This shell cleans
 * both up only after the complete P7/P6 execution returns or throws; cleanup never changes the
 * caller-visible outcome.
 */
public final class StreamingAgentExecutionService {

    private final Function<AgentExecutionRequest, AgentRunResult> execution;
    private final StreamingRunExecutionLifecycle lifecycle;

    public StreamingAgentExecutionService(
            Function<AgentExecutionRequest, AgentRunResult> execution,
            ExecutionStreamHub hub,
            ExecutionStreamPublisher publisher,
            LocalExecutionControlRegistry controls
    ) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.lifecycle = new StreamingRunExecutionLifecycle(hub, publisher, controls);
    }

    StreamingAgentExecutionService(
            Function<AgentExecutionRequest, AgentRunResult> execution,
            StreamingRunExecutionLifecycle lifecycle
    ) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return lifecycle.executeFresh(request, execution);
    }

    /**
     * Executes a fresh run and attaches a live observer synchronously after durable admission
     * opens the run stream, but before Runtime middleware or the first model call can start.
     */
    public AgentRunResult execute(
            AgentExecutionRequest request,
            Consumer<ExecutionStreamSubscription> observer
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        return lifecycle.executeFresh(request, execution, observer);
    }
}
