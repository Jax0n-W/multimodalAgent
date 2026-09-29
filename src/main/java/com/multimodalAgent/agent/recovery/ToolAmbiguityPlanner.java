package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

/** Pure planning only; deferred actions are never executed here. */
public final class ToolAmbiguityPlanner {

    public ToolAmbiguityPlan plan(RecoveryToolEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (evidence.recoveryContract().isEmpty()) {
            return new ToolAmbiguityPlan(
                    ToolAmbiguityAction.CONTRACT_UNAVAILABLE,
                    Optional.empty()
            );
        }
        ToolRecoveryContractSnapshot contract = evidence.recoveryContract().orElseThrow();
        if (contract.reconciliationSupport() == ToolReconciliationSupport.SUPPORTED) {
            return new ToolAmbiguityPlan(
                    ToolAmbiguityAction.RECONCILE,
                    contract.reconciliationStrategyId()
            );
        }
        ToolAmbiguityAction action = switch (contract.replaySemantics()) {
            case REPLAY_SAFE -> ToolAmbiguityAction.REPLAY_DEFERRED;
            case IDEMPOTENT -> ToolAmbiguityAction.IDEMPOTENT_RETRY_DEFERRED;
            case NON_REPLAYABLE -> ToolAmbiguityAction.MANUAL_INTERVENTION;
        };
        return new ToolAmbiguityPlan(action, Optional.empty());
    }
}
