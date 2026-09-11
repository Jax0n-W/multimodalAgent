package com.multimodalAgent.agent.runtime.event;

import java.util.Objects;

public record RunStartedEvent(AgentEventMetadata metadata) implements AgentEvent {

    public RunStartedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.RUN_STARTED;
    }
}
