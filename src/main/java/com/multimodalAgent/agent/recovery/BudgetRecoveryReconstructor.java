package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.budget.BudgetUsage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure reconstruction of work consumed before a crash; no external action is performed. */
public final class BudgetRecoveryReconstructor {

    public RecoveredBudgetUsage reconstruct(
            RecoveryCheckpoint latestCheckpoint,
            List<RecoveryModelEvidence> modelFacts,
            List<RecoveryToolEvidence> toolFacts
    ) {
        Objects.requireNonNull(latestCheckpoint, "latestCheckpoint must not be null");
        Objects.requireNonNull(modelFacts, "modelFacts must not be null");
        Objects.requireNonNull(toolFacts, "toolFacts must not be null");

        BudgetCheckpoint base = latestCheckpoint.budgetUsage();
        long durableModelCalls = modelFacts.stream()
                .filter(this::consumedModelCall)
                .map(RecoveryModelEvidence::stepId)
                .distinct()
                .count();
        long durableToolCalls = toolFacts.stream()
                .filter(this::consumedToolCall)
                .map(RecoveryToolEvidence::executionId)
                .distinct()
                .count();
        long additionalModelCalls = positiveDifference(durableModelCalls, base.modelCalls());
        long additionalToolCalls = positiveDifference(durableToolCalls, base.toolCalls());
        boolean unknownUsage = base.unknownUsageObserved() || additionalModelCalls > 0;
        Optional<BigDecimal> recoveredCost = unknownUsage ? Optional.empty() : base.cost();

        return new RecoveredBudgetUsage(new BudgetUsage(
                Math.addExact(base.modelCalls(), additionalModelCalls),
                Math.addExact(base.toolCalls(), additionalToolCalls),
                base.inputTokens(),
                base.outputTokens(),
                base.totalTokens(),
                recoveredCost,
                unknownUsage
        ));
    }

    private boolean consumedModelCall(RecoveryModelEvidence evidence) {
        Objects.requireNonNull(evidence, "model evidence must not be null");
        return evidence.status() == RecoveryModelStatus.RUNNING
                || evidence.status() == RecoveryModelStatus.SUCCEEDED
                || evidence.status() == RecoveryModelStatus.FAILED;
    }

    private boolean consumedToolCall(RecoveryToolEvidence evidence) {
        Objects.requireNonNull(evidence, "tool evidence must not be null");
        return evidence.status() == RecoveryToolStatus.STARTED
                || evidence.status() == RecoveryToolStatus.UNKNOWN
                || evidence.status() == RecoveryToolStatus.SUCCEEDED
                || evidence.status() == RecoveryToolStatus.FAILED;
    }

    private long positiveDifference(long durableCount, long checkpointCount) {
        return durableCount > checkpointCount ? durableCount - checkpointCount : 0L;
    }
}
