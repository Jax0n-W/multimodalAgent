package com.multimodalAgent.agent.runtime.model;

import java.util.List;
import java.util.Objects;

public record AgentMessage(
        AgentMessageRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName
) {

    public AgentMessage {
        Objects.requireNonNull(role, "role must not be null");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);

        if (role == AgentMessageRole.TOOL
                && (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank())) {
            throw new IllegalArgumentException("Tool messages require toolCallId and toolName");
        }
        if (role != AgentMessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Only assistant messages may contain tool calls");
        }
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(AgentMessageRole.SYSTEM, content, List.of(), null, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(AgentMessageRole.USER, content, List.of(), null, null);
    }

    public static AgentMessage assistant(String content) {
        return new AgentMessage(AgentMessageRole.ASSISTANT, content, List.of(), null, null);
    }

    public static AgentMessage assistantToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Assistant tool-call message must contain at least one call");
        }
        return new AgentMessage(AgentMessageRole.ASSISTANT, "", toolCalls, null, null);
    }

    public static AgentMessage toolResult(String toolCallId, String toolName, String content) {
        return new AgentMessage(AgentMessageRole.TOOL, content, List.of(), toolCallId, toolName);
    }
}
