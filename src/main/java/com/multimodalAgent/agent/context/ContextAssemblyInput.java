package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;

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
        Set<String> allowedTools,
        List<AgentMessage> trustedBusinessContext,
        List<UntrustedEvidence> untrustedEvidence
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
        trustedBusinessContext = List.copyOf(Objects.requireNonNull(
                trustedBusinessContext,
                "trustedBusinessContext must not be null"
        ));
        for (AgentMessage message : trustedBusinessContext) {
            Objects.requireNonNull(message, "trustedBusinessContext must not contain null");
            if (message.role() != AgentMessageRole.SYSTEM
                    || !message.toolCalls().isEmpty()
                    || message.toolCallId() != null
                    || message.toolName() != null) {
                throw new IllegalArgumentException(
                        "trustedBusinessContext must contain plain SYSTEM messages only"
                );
            }
        }
        untrustedEvidence = List.copyOf(Objects.requireNonNull(
                untrustedEvidence,
                "untrustedEvidence must not be null"
        ));
        untrustedEvidence.forEach(evidence -> Objects.requireNonNull(
                evidence,
                "untrustedEvidence must not contain null"
        ));
    }

    public ContextAssemblyInput(
            String runId,
            String sessionId,
            Long userId,
            List<AgentMessage> requestMessages,
            Set<String> allowedTools,
            List<AgentMessage> trustedBusinessContext
    ) {
        this(
                runId, sessionId, userId, requestMessages, allowedTools,
                trustedBusinessContext, List.of()
        );
    }

    public ContextAssemblyInput(
            String runId,
            String sessionId,
            Long userId,
            List<AgentMessage> requestMessages,
            Set<String> allowedTools
    ) {
        this(runId, sessionId, userId, requestMessages, allowedTools, List.of(), List.of());
    }

    public ContextAssemblyInput(
            String runId,
            String sessionId,
            Long userId,
            List<AgentMessage> requestMessages
    ) {
        this(runId, sessionId, userId, requestMessages, Set.of(), List.of(), List.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
