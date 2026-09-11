package com.multimodalAgent.agent.runtime.event;

public record ToolRequestedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName
) implements AgentEvent {

    public ToolRequestedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_REQUESTED;
    }
}
