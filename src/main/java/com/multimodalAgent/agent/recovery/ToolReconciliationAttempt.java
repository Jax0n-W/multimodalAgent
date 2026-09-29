package com.multimodalAgent.agent.recovery;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ToolReconciliationAttempt(
        String reconciliationId,
        String runId,
        String executionId,
        String toolCallId,
        long attemptNo,
        String contractId,
        String strategyId,
        ToolReconciliationAttemptStatus status,
        Optional<ToolReconciliationOutcome> outcome,
        Optional<String> externalReference,
        Optional<String> evidenceSummary,
        Optional<String> errorCode,
        Optional<String> errorMessage,
        Instant startedAt,
        Optional<Instant> completedAt
) {

    public ToolReconciliationAttempt {
        requireText(reconciliationId, "reconciliationId");
        requireText(runId, "runId");
        requireText(executionId, "executionId");
        requireText(toolCallId, "toolCallId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least 1");
        }
        requireText(contractId, "contractId");
        requireText(strategyId, "strategyId");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(externalReference, "externalReference must not be null");
        Objects.requireNonNull(evidenceSummary, "evidenceSummary must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        if (status == ToolReconciliationAttemptStatus.STARTED
                && (outcome.isPresent()
                || errorCode.isPresent()
                || errorMessage.isPresent()
                || completedAt.isPresent())) {
            throw new IllegalArgumentException("STARTED attempt must not contain terminal data");
        }
        if (status == ToolReconciliationAttemptStatus.COMPLETED
                && (outcome.isEmpty() || completedAt.isEmpty())) {
            throw new IllegalArgumentException("COMPLETED attempt requires outcome and completion");
        }
        if (status == ToolReconciliationAttemptStatus.FAILED
                && (errorCode.isEmpty() || errorMessage.isEmpty() || completedAt.isEmpty())) {
            throw new IllegalArgumentException("FAILED attempt requires error and completion");
        }
        if (status == ToolReconciliationAttemptStatus.SUPERSEDED
                && completedAt.isEmpty()) {
            throw new IllegalArgumentException("SUPERSEDED attempt requires completion");
        }
    }

    public ToolReconciliationContext context(String toolName) {
        return new ToolReconciliationContext(
                reconciliationId,
                runId,
                executionId,
                toolCallId,
                toolName,
                attemptNo,
                contractId,
                strategyId
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
