package com.multimodalAgent.agent.recovery;

public interface ToolReconciliationAttemptStore {

    ToolReconciliationStart start(
            String runId,
            String toolCallId,
            ToolRecoveryContractSnapshot contract
    );

    ToolReconciliationAttempt complete(
            String reconciliationId,
            ToolReconciliationResult result
    );

    ToolReconciliationAttempt fail(
            String reconciliationId,
            String errorCode,
            String errorMessage
    );
}
