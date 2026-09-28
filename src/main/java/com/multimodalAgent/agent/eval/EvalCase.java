package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;

/** Immutable, versioned contract oracle for one evaluation execution. */
public record EvalCase(
        String caseId,
        String caseVersion,
        String category,
        List<AgentMessage> messages,
        EvalExecutionConfig execution,
        EvalOracle oracle
) {

    public EvalCase {
        requireText(caseId, "caseId");
        requireText(caseVersion, "caseVersion");
        requireText(category, "category");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        messages = List.copyOf(messages);
        messages.forEach(message -> Objects.requireNonNull(
                message, "messages must not contain null"
        ));
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(oracle, "oracle must not be null");
        if (!execution.allowedTools().containsAll(oracle.expectedTools())) {
            throw new IllegalArgumentException("expectedTools must be allowed");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
