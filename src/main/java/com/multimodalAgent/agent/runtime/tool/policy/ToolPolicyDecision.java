package com.multimodalAgent.agent.runtime.tool.policy;

import java.util.Objects;

public record ToolPolicyDecision(ToolPolicyDecisionType type, String reason) {

    public ToolPolicyDecision {
        Objects.requireNonNull(type, "type must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Policy decision reason must not be blank");
        }
    }

    public static ToolPolicyDecision allow() {
        return new ToolPolicyDecision(ToolPolicyDecisionType.ALLOW, "Tool call is allowed");
    }

    public static ToolPolicyDecision deny(String reason) {
        return new ToolPolicyDecision(ToolPolicyDecisionType.DENY, reason);
    }

    public static ToolPolicyDecision requireApproval(String reason) {
        return new ToolPolicyDecision(ToolPolicyDecisionType.REQUIRE_APPROVAL, reason);
    }
}
