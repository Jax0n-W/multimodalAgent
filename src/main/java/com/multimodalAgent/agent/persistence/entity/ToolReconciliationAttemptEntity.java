package com.multimodalAgent.agent.persistence.entity;

import com.multimodalAgent.agent.recovery.ToolReconciliationAttempt;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttemptStatus;
import com.multimodalAgent.agent.recovery.ToolReconciliationOutcome;
import com.multimodalAgent.agent.recovery.ToolReconciliationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Entity
@Table(name = "tool_reconciliation_attempts")
public class ToolReconciliationAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reconciliation_id", nullable = false, updatable = false, length = 64)
    private String reconciliationId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "tool_execution_id", nullable = false, updatable = false, length = 64)
    private String toolExecutionId;

    @Column(name = "tool_call_id", nullable = false, updatable = false, length = 128)
    private String toolCallId;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private long attemptNo;

    @Column(name = "contract_id", nullable = false, updatable = false, length = 96)
    private String contractId;

    @Column(name = "strategy_id", nullable = false, updatable = false, length = 160)
    private String strategyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ToolReconciliationAttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private ToolReconciliationOutcome outcome;

    @Column(name = "external_reference", length = 500)
    private String externalReference;

    @Column(name = "evidence_summary", length = 2000)
    private String evidenceSummary;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ToolReconciliationAttemptEntity() {
    }

    public ToolReconciliationAttemptEntity(
            String reconciliationId,
            String runId,
            String toolExecutionId,
            String toolCallId,
            long attemptNo,
            String contractId,
            String strategyId,
            Instant startedAt
    ) {
        this.reconciliationId = requireText(reconciliationId, "reconciliationId");
        this.runId = requireText(runId, "runId");
        this.toolExecutionId = requireText(toolExecutionId, "toolExecutionId");
        this.toolCallId = requireText(toolCallId, "toolCallId");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be at least 1");
        }
        this.attemptNo = attemptNo;
        this.contractId = requireText(contractId, "contractId");
        this.strategyId = requireText(strategyId, "strategyId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.createdAt = startedAt;
        this.status = ToolReconciliationAttemptStatus.STARTED;
    }

    public String getReconciliationId() {
        return reconciliationId;
    }

    public String getRunId() {
        return runId;
    }

    public String getToolExecutionId() {
        return toolExecutionId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public long getAttemptNo() {
        return attemptNo;
    }

    public String getContractId() {
        return contractId;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public ToolReconciliationAttemptStatus getStatus() {
        return status;
    }

    public ToolReconciliationOutcome getOutcome() {
        return outcome;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void complete(ToolReconciliationResult result, Instant completedAt) {
        requireStarted();
        Objects.requireNonNull(result, "result must not be null");
        this.status = ToolReconciliationAttemptStatus.COMPLETED;
        this.outcome = result.outcome();
        this.externalReference = result.externalReference().orElse(null);
        this.evidenceSummary = result.evidenceSummary().orElse(null);
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public void fail(String errorCode, String errorMessage, Instant completedAt) {
        requireStarted();
        this.status = ToolReconciliationAttemptStatus.FAILED;
        this.errorCode = requireText(errorCode, "errorCode");
        this.errorMessage = requireText(errorMessage, "errorMessage");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public void supersede(Instant completedAt) {
        if (status == ToolReconciliationAttemptStatus.SUPERSEDED) {
            return;
        }
        if (status != ToolReconciliationAttemptStatus.STARTED
                && status != ToolReconciliationAttemptStatus.COMPLETED) {
            throw new IllegalStateException("Only active or completed attempts can be superseded");
        }
        status = ToolReconciliationAttemptStatus.SUPERSEDED;
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public ToolReconciliationAttempt snapshot() {
        return new ToolReconciliationAttempt(
                reconciliationId,
                runId,
                toolExecutionId,
                toolCallId,
                attemptNo,
                contractId,
                strategyId,
                status,
                Optional.ofNullable(outcome),
                Optional.ofNullable(externalReference),
                Optional.ofNullable(evidenceSummary),
                Optional.ofNullable(errorCode),
                Optional.ofNullable(errorMessage),
                startedAt,
                Optional.ofNullable(completedAt)
        );
    }

    private void requireStarted() {
        if (status != ToolReconciliationAttemptStatus.STARTED) {
            throw new IllegalStateException("Reconciliation attempt is not STARTED");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
