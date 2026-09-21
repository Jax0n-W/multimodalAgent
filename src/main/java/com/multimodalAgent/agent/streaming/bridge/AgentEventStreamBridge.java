package com.multimodalAgent.agent.streaming.bridge;

import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;

import java.util.Objects;

/** Projects existing Runtime facts into the node-local live stream. */
public final class AgentEventStreamBridge implements AgentEventPublisher {

    private final ExecutionStreamPublisher streamPublisher;

    public AgentEventStreamBridge(ExecutionStreamPublisher streamPublisher) {
        this.streamPublisher = Objects.requireNonNull(
                streamPublisher,
                "streamPublisher must not be null"
        );
    }

    @Override
    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        streamPublisher.publish(event.runId(), new RuntimeEventPayload(event));
    }
}
