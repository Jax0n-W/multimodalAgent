package com.multimodalAgent.agent.runtime.event;

public record ToolValidatedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName
) implements AgentEvent {

    public ToolValidatedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_VALIDATED;
    }
}
