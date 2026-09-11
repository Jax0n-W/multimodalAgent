package com.multimodalAgent.agent.runtime.trace;

import com.multimodalAgent.agent.runtime.AgentStopReason;

import java.util.List;
import java.util.Objects;

public record DecisionTrace(
        String runId,
        int eventCount,
        int iterations,
        int modelCallCount,
        int toolCallCount,
        AgentStopReason stopReason,
        boolean waitingApproval,
        List<ToolDecisionTrace> orderedToolDecisions,
        List<DecisionTraceError> errors
) {

    public DecisionTrace {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (eventCount < 1 || iterations < 0 || modelCallCount < 0 || toolCallCount < 0) {
            throw new IllegalArgumentException("Decision trace counts are invalid");
        }
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        orderedToolDecisions = List.copyOf(orderedToolDecisions);
        errors = List.copyOf(errors);
    }
}
