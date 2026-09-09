package com.multimodalAgent.agent.persistence.entity;

import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "tool_executions")
public class ToolExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 64)
    private String executionId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "step_id", nullable = false, updatable = false, length = 64)
    private String stepId;

    @Column(name = "tool_call_id", nullable = false, updatable = false, length = 128)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false, updatable = false, length = 120)
    private String toolName;

    @Column(name = "arguments_hash", length = 128)
    private String argumentsHash;

    @Column(name = "idempotency_key", length = 160)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ToolExecutionStatus status;

    @Column(name = "result_summary", length = 2000)
    private String resultSummary;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ToolExecutionEntity() {
    }

    public ToolExecutionEntity(
            String executionId,
            String runId,
            String stepId,
            String toolCallId,
            String toolName,
            ToolExecutionStatus status
    ) {
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.stepId = Objects.requireNonNull(stepId, "stepId must not be null");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getStepId() {
        return stepId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public void setArgumentsHash(String argumentsHash) {
        this.argumentsHash = argumentsHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public ToolExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ToolExecutionStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
