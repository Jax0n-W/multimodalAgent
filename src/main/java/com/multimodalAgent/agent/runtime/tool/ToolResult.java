package com.multimodalAgent.agent.runtime.tool;

import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;

import java.util.Objects;

public record ToolResult(
        String content,
        ToolError error,
        ToolPolicyDecision policyDecision
) {

    public ToolResult {
        content = content == null ? "" : content;
    }

    public static ToolResult success(String content, ToolPolicyDecision policyDecision) {
        Objects.requireNonNull(policyDecision, "policyDecision must not be null");
        if (policyDecision.type() != ToolPolicyDecisionType.ALLOW) {
            throw new IllegalArgumentException("Successful tool result requires an ALLOW decision");
        }
        return new ToolResult(content, null, policyDecision);
    }

    public static ToolResult failure(ToolErrorCode code, String message) {
        return new ToolResult("", new ToolError(code, message), null);
    }

    public static ToolResult policyBlocked(ToolPolicyDecision policyDecision) {
        requireDecision(policyDecision, ToolPolicyDecisionType.DENY);
        return new ToolResult("", null, policyDecision);
    }

    public static ToolResult approvalRequired(ToolPolicyDecision policyDecision) {
        requireDecision(policyDecision, ToolPolicyDecisionType.REQUIRE_APPROVAL);
        return new ToolResult("", null, policyDecision);
    }

    public boolean success() {
        return error == null
                && policyDecision != null
                && policyDecision.type() == ToolPolicyDecisionType.ALLOW;
    }

    public boolean policyBlocked() {
        return policyDecision != null && policyDecision.type() == ToolPolicyDecisionType.DENY;
    }

    public boolean approvalRequired() {
        return policyDecision != null
                && policyDecision.type() == ToolPolicyDecisionType.REQUIRE_APPROVAL;
    }

    public String messageForModel() {
        if (error != null) {
            return error.message();
        }
        if (policyDecision != null && policyDecision.type() != ToolPolicyDecisionType.ALLOW) {
            return policyDecision.reason();
        }
        return content;
    }

    private static void requireDecision(
            ToolPolicyDecision policyDecision,
            ToolPolicyDecisionType expectedType
    ) {
        Objects.requireNonNull(policyDecision, "policyDecision must not be null");
        if (policyDecision.type() != expectedType) {
            throw new IllegalArgumentException("Expected policy decision " + expectedType);
        }
    }
}
