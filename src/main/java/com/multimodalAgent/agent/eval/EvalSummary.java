package com.multimodalAgent.agent.eval;

import java.math.BigDecimal;
import java.util.Map;

public record EvalSummary(
        int caseCount,
        BigDecimal contractPassRate,
        BigDecimal stopReasonMatchRate,
        BigDecimal toolSelectionCorrectRate,
        long forbiddenToolViolationCount,
        BigDecimal avgModelCalls,
        BigDecimal avgToolCalls,
        BigDecimal avgIterations,
        Long totalInputTokens,
        BigDecimal avgInputTokens,
        Long totalOutputTokens,
        BigDecimal avgOutputTokens,
        Long totalTokens,
        BigDecimal avgTotalTokens,
        BigDecimal usageCompletenessRate,
        BigDecimal estimatedCost,
        BigDecimal costCompletenessRate,
        BigDecimal averageLatencyMillis,
        long p50LatencyMillis,
        long p95LatencyMillis,
        Map<String, Long> stopReasonDistribution,
        Map<String, Long> failureKindDistribution
) {

    public EvalSummary {
        stopReasonDistribution = Map.copyOf(stopReasonDistribution);
        failureKindDistribution = Map.copyOf(failureKindDistribution);
    }
}
