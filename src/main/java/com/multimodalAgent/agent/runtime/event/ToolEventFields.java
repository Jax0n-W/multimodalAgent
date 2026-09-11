package com.multimodalAgent.agent.runtime.event;

import java.util.Objects;

final class ToolEventFields {

    private ToolEventFields() {
    }

    static void require(AgentEventMetadata metadata, String toolCallId, String toolName) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (metadata.iteration() < 1) {
            throw new IllegalArgumentException("Tool event iteration must be at least 1");
        }
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
    }
}
