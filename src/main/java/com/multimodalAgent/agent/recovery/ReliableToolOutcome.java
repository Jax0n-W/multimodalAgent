package com.multimodalAgent.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable durable copy of the exact successful payload shown to the model. */
public record ReliableToolOutcome(
        String executionId,
        String runId,
        String toolCallId,
        String toolName,
        String modelVisibleResult,
        String payloadHash,
        Instant recordedAt,
        int schemaVersion
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ReliableToolOutcome {
        requireText(executionId, "executionId");
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(modelVisibleResult, "modelVisibleResult must not be null");
        requireText(payloadHash, "payloadHash");
        if (!payloadHash.equals(hash(modelVisibleResult))) {
            throw new IllegalArgumentException("payloadHash must match modelVisibleResult");
        }
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        recordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
    }

    public static ReliableToolOutcome capture(
            String executionId,
            String runId,
            String toolCallId,
            String toolName,
            String modelVisibleResult,
            Instant recordedAt
    ) {
        return new ReliableToolOutcome(
                executionId,
                runId,
                toolCallId,
                toolName,
                modelVisibleResult,
                hash(modelVisibleResult),
                recordedAt,
                CURRENT_SCHEMA_VERSION
        );
    }

    public boolean sameExactOutcome(ReliableToolOutcome other) {
        Objects.requireNonNull(other, "other must not be null");
        return executionId.equals(other.executionId)
                && runId.equals(other.runId)
                && toolCallId.equals(other.toolCallId)
                && toolName.equals(other.toolName)
                && modelVisibleResult.equals(other.modelVisibleResult)
                && payloadHash.equals(other.payloadHash)
                && schemaVersion == other.schemaVersion;
    }

    /** Prevents accidental disclosure if an outcome object is sent to an ordinary log. */
    @Override
    public String toString() {
        return "ReliableToolOutcome[executionId=" + executionId
                + ", runId=" + runId
                + ", toolCallId=" + toolCallId
                + ", toolName=" + toolName
                + ", modelVisibleResult=<redacted>"
                + ", payloadHash=" + payloadHash
                + ", recordedAt=" + recordedAt
                + ", schemaVersion=" + schemaVersion + "]";
    }

    public static String hash(String payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
