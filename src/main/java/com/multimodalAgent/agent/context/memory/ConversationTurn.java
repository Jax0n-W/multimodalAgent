package com.multimodalAgent.agent.context.memory;

import java.time.Instant;
import java.util.Objects;

/** One completed durable user/assistant exchange reconstructed from an AgentRun. */
public record ConversationTurn(
        String runId,
        String sessionId,
        Long userId,
        String userContent,
        String assistantContent,
        Instant completedAt
) {

    public ConversationTurn {
        requireText(runId, "runId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(userContent, "userContent must not be null");
        Objects.requireNonNull(assistantContent, "assistantContent must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public int characterCount() {
        return Math.addExact(userContent.length(), assistantContent.length());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
