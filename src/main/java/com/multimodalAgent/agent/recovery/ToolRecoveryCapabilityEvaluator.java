package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

/** Pure projection of execution-time recovery capability; it never executes recovery policy. */
public final class ToolRecoveryCapabilityEvaluator {

    public ToolRecoveryAssessment evaluate(RecoveryToolEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        if (evidence.recoveryContract().isEmpty()) {
            return new ToolRecoveryAssessment(
                    evidence.toolCallId(),
                    evidence.toolName(),
                    evidence.status(),
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    ToolRecoveryAssessmentReason.CONTRACT_UNAVAILABLE
            );
        }
        ToolRecoveryContractSnapshot contract = evidence.recoveryContract().orElseThrow();
        return new ToolRecoveryAssessment(
                evidence.toolCallId(),
                evidence.toolName(),
                evidence.status(),
                true,
                Optional.of(contract.contractId()),
                Optional.of(contract.contractVersion()),
                Optional.of(contract.replaySemantics()),
                Optional.of(contract.reconciliationSupport()),
                contract.reconciliationStrategyId(),
                Optional.of(contract.recoveryClass()),
                ToolRecoveryAssessmentReason.CONTRACT_AVAILABLE
        );
    }
}
