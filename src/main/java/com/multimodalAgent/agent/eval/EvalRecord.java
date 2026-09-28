package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Stable per-case output built only from observed Runtime facts. */
public record EvalRecord(
        String caseId,
        String category,
        boolean contractPass,
        boolean stopReasonMatch,
        boolean toolSelectionCorrect,
        int forbiddenToolViolations,
        AgentStopReason stopReason,
        List<String> toolsUsed,
        long toolRequests,
        List<String> requestedTools,
        List<String> startedTools,
        List<String> succeededTools,
        List<String> failedTools,
        int iterations,
        long modelCalls,
        long toolCalls,
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        TokenUsageStatus tokenUsageStatus,
        BigDecimal estimatedCost,
        CostStatus costStatus,
        long latencyMillis,
        ModelFailureKind modelFailureKind,
        String runtimeConfigSnapshotId,
        EvalSnapshotProvenance runtimeConfigSnapshotProvenance
) {

    public EvalRecord {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        toolsUsed = List.copyOf(Objects.requireNonNull(
                toolsUsed, "toolsUsed must not be null"
        ));
        requestedTools = immutableList(requestedTools, "requestedTools");
        startedTools = immutableList(startedTools, "startedTools");
        succeededTools = immutableList(succeededTools, "succeededTools");
        failedTools = immutableList(failedTools, "failedTools");
        if (toolRequests < 0 || modelCalls < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("event call counts must not be negative");
        }
        if (toolRequests != requestedTools.size()) {
            throw new IllegalArgumentException(
                    "toolRequests must equal the number of requestedTools"
            );
        }
        if (toolCalls != startedTools.size()) {
            throw new IllegalArgumentException(
                    "toolCalls must equal the number of startedTools"
            );
        }
        Objects.requireNonNull(tokenUsageStatus, "tokenUsageStatus must not be null");
        Objects.requireNonNull(costStatus, "costStatus must not be null");
        Objects.requireNonNull(
                runtimeConfigSnapshotProvenance,
                "runtimeConfigSnapshotProvenance must not be null"
        );
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        boolean completeTokens = tokenUsageStatus == TokenUsageStatus.KNOWN;
        if (completeTokens != (inputTokens != null && outputTokens != null && totalTokens != null)) {
            throw new IllegalArgumentException(
                    "Known token usage must provide counts and incomplete usage must use null counts"
            );
        }
        if ((costStatus == CostStatus.KNOWN) != (estimatedCost != null)) {
            throw new IllegalArgumentException("costStatus and estimatedCost must agree");
        }
    }

    private static List<String> immutableList(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
    }
}
