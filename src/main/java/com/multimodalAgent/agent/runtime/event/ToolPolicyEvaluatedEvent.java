package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;

import java.util.Objects;

public record ToolPolicyEvaluatedEvent(
        AgentEventMetadata metadata,
        String toolCallId,
        String toolName,
        ToolPolicyDecisionType decision
) implements AgentEvent {

    public ToolPolicyEvaluatedEvent {
        ToolEventFields.require(metadata, toolCallId, toolName);
        Objects.requireNonNull(decision, "decision must not be null");
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.TOOL_POLICY_EVALUATED;
    }
}
