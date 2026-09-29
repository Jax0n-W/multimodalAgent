package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact continuation state restored from the latest trusted recovery checkpoint. */
public record AgentResumeState(
        List<AgentMessage> messages,
        Set<String> toolsUsed,
        Set<String> seenToolCallIds,
        Set<String> requestedToolCallIds,
        List<ToolCall> pendingToolCalls,
        int currentIteration,
        long checkpointSequence,
        BudgetUsage budgetUsage
) {
    public AgentResumeState {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        toolsUsed = immutableSet(toolsUsed, "toolsUsed");
        seenToolCallIds = immutableSet(seenToolCallIds, "seenToolCallIds");
        requestedToolCallIds = immutableSet(requestedToolCallIds, "requestedToolCallIds");
        pendingToolCalls = List.copyOf(Objects.requireNonNull(
                pendingToolCalls, "pendingToolCalls must not be null"
        ));
        if (currentIteration < 0 || checkpointSequence < 1) {
            throw new IllegalArgumentException("resume position is invalid");
        }
        Objects.requireNonNull(budgetUsage, "budgetUsage must not be null");
    }

    public TokenUsage totalTokenUsage() {
        return new TokenUsage(
                budgetUsage.inputTokens(),
                budgetUsage.outputTokens(),
                budgetUsage.unknownUsageObserved()
                        ? TokenUsageStatus.UNKNOWN_OR_INCOMPLETE
                        : TokenUsageStatus.KNOWN
        );
    }

    private static Set<String> immutableSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name + " must not be null");
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
