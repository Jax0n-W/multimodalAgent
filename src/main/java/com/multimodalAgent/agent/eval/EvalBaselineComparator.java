package com.multimodalAgent.agent.eval;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Reports deltas only; P9.4 intentionally defines no nondeterministic pass threshold. */
public final class EvalBaselineComparator {

    public EvalComparison compare(EvalRun baselineRun, EvalRun currentRun) {
        Objects.requireNonNull(baselineRun, "baselineRun must not be null");
        Objects.requireNonNull(currentRun, "currentRun must not be null");
        requireCompatibleDataset(baselineRun, currentRun);
        EvalSummary baseline = baselineRun.summary();
        EvalSummary current = currentRun.summary();
        TreeMap<String, Long> failureDelta = new TreeMap<>();
        HashSet<String> failureKinds = new HashSet<>(
                baseline.failureKindDistribution().keySet()
        );
        failureKinds.addAll(current.failureKindDistribution().keySet());
        for (String kind : failureKinds) {
            failureDelta.put(
                    kind,
                    current.failureKindDistribution().getOrDefault(kind, 0L)
                            - baseline.failureKindDistribution().getOrDefault(kind, 0L)
            );
        }
        return new EvalComparison(
                current.contractPassRate().subtract(baseline.contractPassRate()),
                current.toolSelectionCorrectRate().subtract(
                        baseline.toolSelectionCorrectRate()
                ),
                current.avgModelCalls().subtract(baseline.avgModelCalls()),
                current.avgToolCalls().subtract(baseline.avgToolCalls()),
                delta(baseline.totalInputTokens(), current.totalInputTokens()),
                delta(baseline.totalOutputTokens(), current.totalOutputTokens()),
                delta(baseline.totalTokens(), current.totalTokens()),
                delta(baseline.estimatedCost(), current.estimatedCost()),
                current.p95LatencyMillis() - baseline.p95LatencyMillis(),
                failureDelta
        );
    }

    private void requireCompatibleDataset(EvalRun baseline, EvalRun current) {
        if (!baseline.metadata().suiteId().equals(current.metadata().suiteId())) {
            throw incompatible("suiteId differs");
        }
        if (!baseline.metadata().suiteVersion().equals(current.metadata().suiteVersion())) {
            throw incompatible("suiteVersion differs");
        }
        Set<String> baselineCases = caseIds(baseline);
        Set<String> currentCases = caseIds(current);
        if (!baselineCases.equals(currentCases)) {
            throw incompatible("caseId set differs");
        }
    }

    private Set<String> caseIds(EvalRun run) {
        HashSet<String> ids = new HashSet<>();
        run.records().forEach(record -> ids.add(record.caseId()));
        return Set.copyOf(ids);
    }

    private IncompatibleEvalBaselineException incompatible(String detail) {
        return new IncompatibleEvalBaselineException(detail);
    }

    private Long delta(Long baseline, Long current) {
        return baseline == null || current == null ? null : current - baseline;
    }

    private BigDecimal delta(BigDecimal baseline, BigDecimal current) {
        return baseline == null || current == null ? null : current.subtract(baseline);
    }
}
