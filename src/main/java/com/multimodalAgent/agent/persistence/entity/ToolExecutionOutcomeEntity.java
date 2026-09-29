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
@Table(name = "tool_execution_outcomes")
public class ToolExecutionOutcomeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false, length = 64)
    private String executionId;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "tool_call_id", nullable = false, updatable = false, length = 128)
    private String toolCallId;

    @Column(name = "tool_name", nullable = false, updatable = false, length = 120)
    private String toolName;

    @Lob
    @Column(name = "result_payload", nullable = false, updatable = false)
    private String resultPayload;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    protected ToolExecutionOutcomeEntity() {
    }

    public ToolExecutionOutcomeEntity(
            String executionId,
            String runId,
            String toolCallId,
            String toolName,
            String resultPayload,
            String payloadHash,
            Instant recordedAt,
            int schemaVersion
    ) {
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.resultPayload = Objects.requireNonNull(resultPayload, "resultPayload must not be null");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash must not be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        this.schemaVersion = schemaVersion;
    }

    public String getExecutionId() {
        return executionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getResultPayload() {
        return resultPayload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }
}
