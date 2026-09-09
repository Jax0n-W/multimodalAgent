package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AgentRunSpec(
        String runId,
        String sessionId,
        List<AgentMessage> messages,
        int maxIterations,
        Set<String> allowedTools, // 允许调用的工具名称集合
        Set<String> approvedToolCallIds
) {

    public AgentRunSpec(
            String runId,
            String sessionId,
            List<AgentMessage> messages,
            int maxIterations
    ) {
        this(runId, sessionId, messages, maxIterations, Set.of(), Set.of());
    }

    public AgentRunSpec {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
        Objects.requireNonNull(allowedTools, "allowedTools must not be null");
        Objects.requireNonNull(approvedToolCallIds, "approvedToolCallIds must not be null");
        allowedTools = Set.copyOf(allowedTools);
        approvedToolCallIds = Set.copyOf(approvedToolCallIds);
    }
}
