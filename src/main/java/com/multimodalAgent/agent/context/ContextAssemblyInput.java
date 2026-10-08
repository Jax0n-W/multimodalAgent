package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;

public record ContextAssemblyInput(
        String runId,
        String sessionId,
        Long userId,
        List<AgentMessage> requestMessages
) {

    public ContextAssemblyInput {
        requireText(runId, "runId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId must not be null");
        requestMessages = List.copyOf(Objects.requireNonNull(
                requestMessages,
                "requestMessages must not be null"
        ));
        if (requestMessages.isEmpty()) {
            throw new IllegalArgumentException("requestMessages must not be empty");
        }
        requestMessages.forEach(message -> Objects.requireNonNull(
                message,
                "requestMessages must not contain null"
        ));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
