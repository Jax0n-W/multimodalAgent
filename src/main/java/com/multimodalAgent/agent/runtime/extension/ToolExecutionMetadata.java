package com.multimodalAgent.agent.runtime.extension;

public record ToolExecutionMetadata(String toolCallId, String toolName, int iteration) {

    public ToolExecutionMetadata {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
    }
}
