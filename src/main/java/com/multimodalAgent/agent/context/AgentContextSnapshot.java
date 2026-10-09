package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/** Immutable initial model-visible context and its durable provenance. */
public record AgentContextSnapshot(
        String snapshotId,
        int schemaVersion,
        String runId,
        String sessionId,
        Long userId,
        List<ContextProvenance> orderedContributions,
        List<AgentMessage> messages,
        Instant createdAt,
        String contextHash,
        String canonicalJson
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentContextSnapshot {
        requireText(snapshotId, "snapshotId");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported context snapshot schemaVersion: " + schemaVersion
            );
        }
        requireText(runId, "runId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId must not be null");
        orderedContributions = List.copyOf(Objects.requireNonNull(
                orderedContributions,
                "orderedContributions must not be null"
        ));
        orderedContributions.forEach(value -> Objects.requireNonNull(
                value,
                "orderedContributions must not contain null"
        ));
        messages = ContextSemanticContent.freezeMessages(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages.forEach(message -> Objects.requireNonNull(
                message,
                "messages must not contain null"
        ));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null")
                .truncatedTo(ChronoUnit.MICROS);
        if (contextHash == null || !contextHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contextHash must be a lowercase SHA-256 hex value");
        }
        requireText(canonicalJson, "canonicalJson");
        if (!("context-v" + schemaVersion + "-" + contextHash).equals(snapshotId)) {
            throw new IllegalArgumentException(
                    "snapshotId must be derived from schemaVersion and contextHash"
            );
        }
    }

    public boolean sameSemanticContent(AgentContextSnapshot other) {
        Objects.requireNonNull(other, "other must not be null");
        return snapshotId.equals(other.snapshotId)
                && schemaVersion == other.schemaVersion
                && runId.equals(other.runId)
                && sessionId.equals(other.sessionId)
                && userId.equals(other.userId)
                && orderedContributions.equals(other.orderedContributions)
                && contextHash.equals(other.contextHash)
                && canonicalJson.equals(other.canonicalJson);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
