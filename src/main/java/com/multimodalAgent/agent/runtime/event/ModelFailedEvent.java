package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.AgentStopReason;

import java.util.Objects;

public record ModelFailedEvent(
        AgentEventMetadata metadata,
        AgentStopReason stopReason
) implements AgentEvent {

    public ModelFailedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        if (metadata.iteration() < 1) {
            throw new IllegalArgumentException("ModelFailedEvent iteration must be at least 1");
        }
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.MODEL_FAILED;
    }
}
