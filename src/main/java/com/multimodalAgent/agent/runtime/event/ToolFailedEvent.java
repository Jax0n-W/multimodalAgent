package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;

import java.util.Objects;

public record ToolFailedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName,
        ToolErrorCode errorCode
) implements AgentEvent {

    public ToolFailedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
        Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_FAILED;
    }
}
