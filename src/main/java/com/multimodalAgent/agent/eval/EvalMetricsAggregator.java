package com.multimodalAgent.agent.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Deterministic summary aggregation using nearest-rank percentiles. */
public final class EvalMetricsAggregator {

    private static final int SCALE = 6;

    public EvalSummary aggregate(List<EvalRecord> records) {
        Objects.requireNonNull(records, "records must not be null");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("records must not be empty");
        }
        int count = records.size();
        long completeUsage = records.stream()
                .filter(record -> record.inputTokens() != null)
                .count();
        long completeCost = records.stream()
                .filter(record -> record.costStatus() == CostStatus.KNOWN)
                .count();
        boolean allUsageComplete = completeUsage == count;
        boolean allCostComplete = completeCost == count;
        Long totalInput = allUsageComplete
                ? records.stream().mapToLong(EvalRecord::inputTokens).sum()
                : null;
        Long totalOutput = allUsageComplete
                ? records.stream().mapToLong(EvalRecord::outputTokens).sum()
                : null;
        Long total = allUsageComplete
                ? records.stream().mapToLong(EvalRecord::totalTokens).sum()
                : null;
        BigDecimal cost = allCostComplete
                ? records.stream().map(EvalRecord::estimatedCost)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        List<Long> latencies = records.stream().map(EvalRecord::latencyMillis).toList();
        return new EvalSummary(
                count,
                rate(records, EvalRecord::contractPass),
                rate(records, EvalRecord::stopReasonMatch),
                rate(records, EvalRecord::toolSelectionCorrect),
                records.stream().mapToLong(EvalRecord::forbiddenToolViolations).sum(),
                average(records.stream().mapToLong(EvalRecord::modelCalls).sum(), count),
                average(records.stream().mapToLong(EvalRecord::toolCalls).sum(), count),
                average(records.stream().mapToLong(EvalRecord::iterations).sum(), count),
                totalInput,
                totalInput == null ? null : average(totalInput, count),
                totalOutput,
                totalOutput == null ? null : average(totalOutput, count),
                total,
                total == null ? null : average(total, count),
                ratio(completeUsage, count),
                cost,
                ratio(completeCost, count),
                average(records.stream().mapToLong(EvalRecord::latencyMillis).sum(), count),
                percentileNearestRank(latencies, 0.50),
                percentileNearestRank(latencies, 0.95),
                distribution(records.stream().map(record -> record.stopReason().name()).toList()),
                distribution(records.stream()
                        .filter(record -> record.modelFailureKind() != null)
                        .map(record -> record.modelFailureKind().name())
                        .toList())
        );
    }

    /** Nearest-rank percentile: sorted value at {@code ceil(p * N)}, using one-based ranks. */
    public static long percentileNearestRank(List<Long> values, double percentile) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        if (percentile <= 0.0 || percentile > 1.0) {
            throw new IllegalArgumentException("percentile must be in (0, 1]");
        }
        ArrayList<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int rank = (int) Math.ceil(percentile * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    private BigDecimal rate(List<EvalRecord> records, Predicate<EvalRecord> predicate) {
        return ratio(records.stream().filter(predicate).count(), records.size());
    }

    private BigDecimal ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal average(long total, long count) {
        return BigDecimal.valueOf(total)
                .divide(BigDecimal.valueOf(count), SCALE, RoundingMode.HALF_UP);
    }

    private Map<String, Long> distribution(List<String> values) {
        TreeMap<String, Long> distribution = new TreeMap<>();
        for (String value : values) {
            distribution.merge(value, 1L, Long::sum);
        }
        return distribution;
    }
}
