package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;

import java.util.Objects;

public record RunStoppedEvent(
        AgentEventMetadata metadata,
        AgentStopReason stopReason,
        ToolErrorCode toolErrorCode
) implements AgentEvent {

    public RunStoppedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        if (stopReason == AgentStopReason.COMPLETED
                || stopReason == AgentStopReason.WAITING_APPROVAL) {
            throw new IllegalArgumentException("RunStoppedEvent requires a non-success terminal reason");
        }
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.RUN_STOPPED;
    }
}
