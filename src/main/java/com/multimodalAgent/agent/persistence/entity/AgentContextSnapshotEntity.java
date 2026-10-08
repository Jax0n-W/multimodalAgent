package com.multimodalAgent.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_context_snapshots")
public class AgentContextSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, updatable = false, length = 96)
    private String snapshotId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "session_id", nullable = false, updatable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "context_hash", nullable = false, updatable = false, length = 64)
    private String contextHash;

    @Lob
    @Column(name = "context_json", nullable = false, updatable = false)
    private String contextJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentContextSnapshotEntity() {
    }

    public AgentContextSnapshotEntity(
            String snapshotId,
            int schemaVersion,
            String runId,
            String sessionId,
            Long userId,
            String contextHash,
            String contextJson,
            Instant createdAt
    ) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        this.schemaVersion = schemaVersion;
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.contextHash = Objects.requireNonNull(contextHash, "contextHash must not be null");
        this.contextJson = Objects.requireNonNull(contextJson, "contextJson must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getRunId() {
        return runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContextHash() {
        return contextHash;
    }

    public String getContextJson() {
        return contextJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
