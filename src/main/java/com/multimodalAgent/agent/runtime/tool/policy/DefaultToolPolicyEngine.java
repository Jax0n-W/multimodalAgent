package com.multimodalAgent.agent.runtime.tool.policy;

import java.util.Objects;

public final class DefaultToolPolicyEngine implements ToolPolicyEngine {

    @Override
    public ToolPolicyDecision evaluate(ToolPolicyRequest request) {
        Objects.requireNonNull(request, "request must not be null"); // 确保请求对象不为空
        String toolName = request.descriptor().name(); // 获取工具名称

        if (!request.context().allowedTools().contains(toolName)) { // 如果工具名称不在允许调用的工具名称集合中
            return ToolPolicyDecision.deny("Tool is not allowed for this run: " + toolName);
        }

        if (request.descriptor().requiresApproval()  // 如果工具需要审批
                               && !request.context().approvedToolCallIds().contains(request.toolCall().id())) {
            return ToolPolicyDecision.requireApproval( // 如果工具调用ID不在已审批工具调用ID集合中
                    "Tool call requires approval: " + request.toolCall().id()
            );
        }

        return ToolPolicyDecision.allow();
    }
}
