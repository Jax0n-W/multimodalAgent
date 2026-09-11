package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;

import java.util.Objects;

public record ToolValidationFailedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName,
        ToolErrorCode errorCode
) implements AgentEvent {

    public ToolValidationFailedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        if (errorCode != ToolErrorCode.INVALID_ARGUMENTS) {
            throw new IllegalArgumentException("ToolValidationFailedEvent requires INVALID_ARGUMENTS");
        }
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_VALIDATION_FAILED;
    }
}
