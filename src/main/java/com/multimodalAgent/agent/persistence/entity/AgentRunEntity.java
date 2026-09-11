package com.multimodalAgent.agent.persistence.entity;

import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.runtime.AgentStopReason;
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
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_runs")
public class AgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 128)
    private String requestId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentRunPhase phase;

    @Column(name = "model_version", length = 120)
    private String modelVersion;

    @Column(name = "prompt_version", length = 120)
    private String promptVersion;

    @Column(name = "skill_version", length = 120)
    private String skillVersion;

    @Column(name = "current_iteration", nullable = false)
    private int currentIteration;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "final_content", length = 12000)
    private String finalContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 40)
    private AgentStopReason stopReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected AgentRunEntity() {
    }

    public AgentRunEntity(
            String runId,
            String requestId,
            Long userId,
            String sessionId,
            AgentRunStatus status,
            AgentRunPhase phase
    ) {
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.requestId = Objects.requireNonNull(requestId, "requestId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
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

    public String getRunId() {
        return runId;
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public AgentRunStatus getStatus() {
        return status;
    }

    public void setStatus(AgentRunStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public AgentRunPhase getPhase() {
        return phase;
    }

    public void setPhase(AgentRunPhase phase) {
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getSkillVersion() {
        return skillVersion;
    }

    public void setSkillVersion(String skillVersion) {
        this.skillVersion = skillVersion;
    }

    public int getCurrentIteration() {
        return currentIteration;
    }

    public void setCurrentIteration(int currentIteration) {
        if (currentIteration < 0) {
            throw new IllegalArgumentException("currentIteration must not be negative");
        }
        this.currentIteration = currentIteration;
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

    public String getFinalContent() {
        return finalContent;
    }

    public void setFinalContent(String finalContent) {
        this.finalContent = finalContent;
    }

    public AgentStopReason getStopReason() {
        return stopReason;
    }

    public void setStopReason(AgentStopReason stopReason) {
        this.stopReason = stopReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
