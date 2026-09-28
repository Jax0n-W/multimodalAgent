package com.multimodalAgent.agent.eval;

import java.math.BigDecimal;
import java.util.Map;

public record EvalComparison(
        BigDecimal contractPassRateDelta,
        BigDecimal toolSelectionCorrectRateDelta,
        BigDecimal avgModelCallsDelta,
        BigDecimal avgToolCallsDelta,
        Long inputTokensDelta,
        Long outputTokensDelta,
        Long totalTokensDelta,
        BigDecimal estimatedCostDelta,
        long p95LatencyMillisDelta,
        Map<String, Long> failureDistributionDelta
) {

    public EvalComparison {
        failureDistributionDelta = Map.copyOf(failureDistributionDelta);
    }
}
