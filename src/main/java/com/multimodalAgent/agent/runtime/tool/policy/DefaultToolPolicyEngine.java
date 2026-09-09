package com.multimodalAgent.agent.runtime.tool.policy;

import java.util.Objects;

public final class DefaultToolPolicyEngine implements ToolPolicyEngine {

    @Override
    public ToolPolicyDecision evaluate(ToolPolicyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String toolName = request.descriptor().name();

        if (!request.context().allowedTools().contains(toolName)) {
            return ToolPolicyDecision.deny("Tool is not allowed for this run: " + toolName);
        }

        if (request.descriptor().requiresApproval()
                && !request.context().approvedToolCallIds().contains(request.toolCall().id())) {
            return ToolPolicyDecision.requireApproval(
                    "Tool call requires approval: " + request.toolCall().id()
            );
        }

        return ToolPolicyDecision.allow();
    }
}
