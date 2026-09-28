package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Complete continuation state captured at one confirmed safe execution boundary. */
public record RecoveryCheckpoint(
        String checkpointId,
        String runId,
        long sequence,
        int iteration,
        RecoveryCheckpointBoundary boundary,
        List<AgentMessage> messages,
        List<String> toolsUsed,
        Set<String> seenToolCallIds,
        BudgetCheckpoint budgetUsage,
        Set<String> approvedToolCallIds,
        String runtimeConfigSnapshotId,
        Instant createdAt,
        int schemaVersion
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RecoveryCheckpoint {
        requireText(checkpointId, "checkpointId");
        requireText(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        Objects.requireNonNull(toolsUsed, "toolsUsed must not be null");
        toolsUsed = List.copyOf(toolsUsed);
        requireTextElements(toolsUsed, "toolsUsed");
        seenToolCallIds = immutableTextSet(seenToolCallIds, "seenToolCallIds");
        Objects.requireNonNull(budgetUsage, "budgetUsage must not be null");
        approvedToolCallIds = immutableTextSet(approvedToolCallIds, "approvedToolCallIds");
        requireText(runtimeConfigSnapshotId, "runtimeConfigSnapshotId");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
    }

    private static Set<String> immutableTextSet(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        requireTextElements(values, field);
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static void requireTextElements(Iterable<String> values, String field) {
        for (String value : values) {
            requireText(value, field + " element");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
