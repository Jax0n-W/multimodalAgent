package com.multimodalAgent.agent.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "agent_recovery_checkpoints")
public class AgentRecoveryCheckpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "checkpoint_id", nullable = false, updatable = false, length = 96)
    private String checkpointId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "checkpoint_sequence", nullable = false, updatable = false)
    private long checkpointSequence;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "iteration", nullable = false, updatable = false)
    private int iteration;

    @Column(name = "boundary", nullable = false, updatable = false, length = 40)
    private String boundary;

    @Lob
    @Column(name = "state_json", nullable = false, updatable = false)
    private String stateJson;

    @Column(name = "runtime_config_snapshot_id", nullable = false, updatable = false, length = 96)
    private String runtimeConfigSnapshotId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AgentRecoveryCheckpointEntity() {
    }

    public AgentRecoveryCheckpointEntity(
            String checkpointId,
            String runId,
            long checkpointSequence,
            int schemaVersion,
            int iteration,
            String boundary,
            String stateJson,
            String runtimeConfigSnapshotId,
            Instant createdAt
    ) {
        this.checkpointId = Objects.requireNonNull(checkpointId, "checkpointId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.checkpointSequence = checkpointSequence;
        this.schemaVersion = schemaVersion;
        this.iteration = iteration;
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
        this.stateJson = Objects.requireNonNull(stateJson, "stateJson must not be null");
        this.runtimeConfigSnapshotId = Objects.requireNonNull(
                runtimeConfigSnapshotId,
                "runtimeConfigSnapshotId must not be null"
        );
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public Long getId() {
        return id;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public String getRunId() {
        return runId;
    }

    public long getCheckpointSequence() {
        return checkpointSequence;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public int getIteration() {
        return iteration;
    }

    public String getBoundary() {
        return boundary;
    }

    public String getStateJson() {
        return stateJson;
    }

    public String getRuntimeConfigSnapshotId() {
        return runtimeConfigSnapshotId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
