package com.multimodalAgent.agent.runtime.event;

import java.util.Objects;

public record RunCompletedEvent(AgentEventMetadata metadata) implements AgentEvent {

    public RunCompletedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.RUN_COMPLETED;
    }
}
