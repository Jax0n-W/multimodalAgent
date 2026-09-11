package com.multimodalAgent.agent.runtime.event;

import java.time.Instant;
import java.util.Objects;

public record AgentEventMetadata(
        String eventId,
        String runId,
        long sequence,
        Instant occurredAt,
        int iteration
) {

    public AgentEventMetadata {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
    }
}
