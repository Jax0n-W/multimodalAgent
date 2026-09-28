package com.multimodalAgent.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_runtime_config_snapshots")
public class AgentRuntimeConfigSnapshotEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false, updatable = false, length = 96)
    private String snapshotId;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "config_hash", nullable = false, updatable = false, length = 64)
    private String configHash;

    @Lob
    @Column(name = "config_json", nullable = false, updatable = false)
    private String configJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentRuntimeConfigSnapshotEntity() {
    }

    public AgentRuntimeConfigSnapshotEntity(
            String snapshotId,
            int schemaVersion,
            String configHash,
            String configJson
    ) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        this.schemaVersion = schemaVersion;
        this.configHash = Objects.requireNonNull(configHash, "configHash must not be null");
        this.configJson = Objects.requireNonNull(configJson, "configJson must not be null");
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getConfigHash() {
        return configHash;
    }

    public String getConfigJson() {
        return configJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
