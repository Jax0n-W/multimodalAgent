package com.multimodalAgent.agent.runtime.event;

import java.util.Objects;

public record ModelStartedEvent(AgentEventMetadata metadata) implements AgentEvent {

    public ModelStartedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (metadata.iteration() < 1) {
            throw new IllegalArgumentException("ModelStartedEvent iteration must be at least 1");
        }
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.MODEL_STARTED;
    }
}
