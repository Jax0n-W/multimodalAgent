package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;

import java.util.Objects;

public record ModelCompletedEvent(
        AgentEventMetadata metadata,
        ModelFinishReason finishReason,
        int toolCallCount,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        TokenUsageStatus tokenUsageStatus
) implements AgentEvent {

    public ModelCompletedEvent(
            AgentEventMetadata metadata,
            ModelFinishReason finishReason,
            int toolCallCount,
            long inputTokens,
            long outputTokens,
            long totalTokens
    ) {
        this(
                metadata, finishReason, toolCallCount,
                inputTokens, outputTokens, totalTokens, TokenUsageStatus.KNOWN
        );
    }

    public ModelCompletedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        Objects.requireNonNull(tokenUsageStatus, "tokenUsageStatus must not be null");
        if (metadata.iteration() < 1) {
            throw new IllegalArgumentException("ModelCompletedEvent iteration must be at least 1");
        }
        if (toolCallCount < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("ModelCompletedEvent counts must not be negative");
        }
        if (totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("totalTokens must equal inputTokens + outputTokens");
        }
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.MODEL_COMPLETED;
    }
}
