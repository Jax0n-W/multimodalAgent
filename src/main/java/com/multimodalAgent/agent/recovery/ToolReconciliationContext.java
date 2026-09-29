package com.multimodalAgent.agent.recovery;

public record ToolReconciliationContext(
        String reconciliationId,
        String runId,
        String executionId,
        String toolCallId,
        String toolName,
        long attemptNo,
        String contractId,
        String strategyId
) {

    public ToolReconciliationContext {
        requireText(reconciliationId, "reconciliationId");
        requireText(runId, "runId");
        requireText(executionId, "executionId");
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least 1");
        }
        requireText(contractId, "contractId");
        requireText(strategyId, "strategyId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
