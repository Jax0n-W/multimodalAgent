package com.multimodalAgent.agent.context.memory;

import java.util.Objects;

/** Isolated, bounded query for prior turns of one authenticated user session. */
public record ConversationMemoryQuery(
        Long userId,
        String sessionId,
        String currentRunId,
        int limit
) {

    public ConversationMemoryQuery {
        Objects.requireNonNull(userId, "userId must not be null");
        requireText(sessionId, "sessionId");
        requireText(currentRunId, "currentRunId");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
