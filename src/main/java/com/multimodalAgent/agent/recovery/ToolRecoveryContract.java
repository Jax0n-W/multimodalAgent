package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolRecoveryContract(
        String contractVersion,
        ToolReplaySemantics replaySemantics,
        ToolReconciliationSupport reconciliationSupport,
        Optional<String> reconciliationStrategyId
) {

    public static final String DEFAULT_CONTRACT_VERSION = "v1";

    public ToolRecoveryContract {
        requireText(contractVersion, "contractVersion");
        Objects.requireNonNull(replaySemantics, "replaySemantics must not be null");
        Objects.requireNonNull(
                reconciliationSupport,
                "reconciliationSupport must not be null"
        );
        Objects.requireNonNull(
                reconciliationStrategyId,
                "reconciliationStrategyId must not be null"
        );
        reconciliationStrategyId.ifPresent(value ->
                requireText(value, "reconciliationStrategyId")
        );
        if (reconciliationSupport == ToolReconciliationSupport.SUPPORTED
                && reconciliationStrategyId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Supported reconciliation requires a strategy identity"
            );
        }
        if (reconciliationSupport == ToolReconciliationSupport.UNSUPPORTED
                && reconciliationStrategyId.isPresent()) {
            throw new IllegalArgumentException(
                    "Unsupported reconciliation must not declare a strategy identity"
            );
        }
    }

    public static ToolRecoveryContract defaults(boolean readOnly, boolean idempotent) {
        ToolReplaySemantics replay = readOnly
                ? ToolReplaySemantics.REPLAY_SAFE
                : idempotent
                ? ToolReplaySemantics.IDEMPOTENT
                : ToolReplaySemantics.NON_REPLAYABLE;
        return new ToolRecoveryContract(
                DEFAULT_CONTRACT_VERSION,
                replay,
                ToolReconciliationSupport.UNSUPPORTED,
                Optional.empty()
        );
    }

    public ToolRecoveryContractSnapshot snapshot(String toolName) {
        return ToolRecoveryContractSnapshot.create(toolName, this);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
