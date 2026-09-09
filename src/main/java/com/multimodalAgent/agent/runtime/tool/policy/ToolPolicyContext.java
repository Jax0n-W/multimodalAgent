package com.multimodalAgent.agent.runtime.tool.policy;

import java.util.Objects;
import java.util.Set;

public record ToolPolicyContext(
        String runId,
        String sessionId,
        Set<String> allowedTools,
        Set<String> approvedToolCallIds
) {

    public ToolPolicyContext {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(allowedTools, "allowedTools must not be null");
        Objects.requireNonNull(approvedToolCallIds, "approvedToolCallIds must not be null");
        allowedTools = Set.copyOf(allowedTools);
        approvedToolCallIds = Set.copyOf(approvedToolCallIds);
    }
}
