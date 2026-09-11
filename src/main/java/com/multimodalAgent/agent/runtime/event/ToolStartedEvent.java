package com.multimodalAgent.agent.runtime.event;

public record ToolStartedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName
) implements AgentEvent {

    public ToolStartedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_STARTED;
    }
}
