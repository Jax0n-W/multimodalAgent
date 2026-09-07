package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;

import java.util.List;
import java.util.Objects;

public record AgentRunResult(
        String finalContent,
        AgentStopReason stopReason,
        int iterations,
        List<String> toolsUsed,
        List<AgentMessage> messages,
        TokenUsage tokenUsage,
        ToolErrorCode toolErrorCode,
        String errorMessage
) {

    public AgentRunResult {
        finalContent = finalContent == null ? "" : finalContent;
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        toolsUsed = List.copyOf(toolsUsed);
        messages = List.copyOf(messages);
        tokenUsage = tokenUsage == null ? TokenUsage.ZERO : tokenUsage;
    }
}
