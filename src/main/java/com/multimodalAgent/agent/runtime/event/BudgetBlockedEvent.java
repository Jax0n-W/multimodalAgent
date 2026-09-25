package com.multimodalAgent.agent.runtime.event;

import com.multimodalAgent.agent.runtime.budget.BudgetBlock;
import com.multimodalAgent.agent.runtime.budget.BudgetBlockReason;
import com.multimodalAgent.agent.runtime.budget.BudgetDimension;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record BudgetBlockedEvent(
        AgentEventMetadata metadata,
        BudgetDimension dimension,
        BudgetBlockReason reason,
        BigDecimal limit,
        Optional<BigDecimal> observed,
        Optional<String> toolCallId,
        Optional<String> toolName
) implements AgentEvent {

    public BudgetBlockedEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolCallId.isPresent() != toolName.isPresent()) {
            throw new IllegalArgumentException(
                    "toolCallId and toolName must either both be present or both be absent"
            );
        }
        if (dimension == BudgetDimension.TOOL_CALLS && toolCallId.isEmpty()) {
            throw new IllegalArgumentException("TOOL_CALLS budget block requires tool correlation");
        }
        if (dimension == BudgetDimension.MODEL_CALLS && toolCallId.isPresent()) {
            throw new IllegalArgumentException("MODEL_CALLS budget block cannot correlate a tool");
        }
        toolCallId.ifPresent(value -> requireText(value, "toolCallId"));
        toolName.ifPresent(value -> requireText(value, "toolName"));
        // Reuse the value-level invariants owned by the budget domain.
        new BudgetBlock(dimension, reason, limit, observed);
    }

    public static BudgetBlockedEvent forRun(
            AgentEventMetadata metadata,
            BudgetBlock block
    ) {
        return new BudgetBlockedEvent(
                metadata, block.dimension(), block.reason(), block.limit(), block.observed(),
                Optional.empty(), Optional.empty()
        );
    }

    public static BudgetBlockedEvent forTool(
            AgentEventMetadata metadata,
            BudgetBlock block,
            String toolCallId,
            String toolName
    ) {
        return new BudgetBlockedEvent(
                metadata, block.dimension(), block.reason(), block.limit(), block.observed(),
                Optional.of(toolCallId), Optional.of(toolName)
        );
    }

    public boolean toolCorrelated() {
        return toolCallId.isPresent();
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.BUDGET_BLOCKED;
    }

    private static void requireText(String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
