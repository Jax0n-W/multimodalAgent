package com.multimodalAgent.agent.runtime.trace;

import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;

public record ToolDecisionTrace(
        String toolCallId,
        String toolName,
        int iteration,
        ToolPolicyDecisionType policyDecision,
        ToolExecutionOutcome executionOutcome,
        ToolErrorCode errorCode
) {

    public ToolDecisionTrace {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (executionOutcome == null) {
            throw new IllegalArgumentException("executionOutcome must not be null");
        }
    }
}
