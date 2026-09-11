package com.multimodalAgent.agent.runtime.event;

public record ToolSucceededEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName
) implements AgentEvent {

    public ToolSucceededEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_SUCCEEDED;
    }
}
