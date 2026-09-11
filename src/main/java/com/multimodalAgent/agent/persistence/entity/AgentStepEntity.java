package com.multimodalAgent.agent.persistence.entity;

import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_steps")
public class AgentStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_id", nullable = false, updatable = false, length = 64)
    private String stepId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(nullable = false)
    private int iteration;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 32)
    private AgentStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentStepStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentStepEntity() {
    }

    public AgentStepEntity(
            String stepId,
            String runId,
            int iteration,
            int stepIndex,
            AgentStepType stepType,
            AgentStepStatus status
    ) {
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (stepIndex < 1) {
            throw new IllegalArgumentException("stepIndex must be at least 1");
        }
        this.stepId = Objects.requireNonNull(stepId, "stepId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.iteration = iteration;
        this.stepIndex = stepIndex;
        this.stepType = Objects.requireNonNull(stepType, "stepType must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getStepId() {
        return stepId;
    }

    public String getRunId() {
        return runId;
    }

    public int getIteration() {
        return iteration;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public AgentStepType getStepType() {
        return stepType;
    }

    public AgentStepStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStepStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
