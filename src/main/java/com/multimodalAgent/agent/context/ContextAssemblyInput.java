package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ContextAssemblyInput(
        String runId,
        String sessionId,
        Long userId,
        List<AgentMessage> requestMessages,
        Set<String> allowedTools
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
        Objects.requireNonNull(allowedTools, "allowedTools must not be null");
        TreeSet<String> sortedTools = new TreeSet<>();
        for (String tool : allowedTools) {
            requireText(tool, "allowedTools value");
            sortedTools.add(tool);
        }
        allowedTools = Collections.unmodifiableSet(sortedTools);
    }

    public ContextAssemblyInput(
            String runId,
            String sessionId,
            Long userId,
            List<AgentMessage> requestMessages
    ) {
        this(runId, sessionId, userId, requestMessages, Set.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
