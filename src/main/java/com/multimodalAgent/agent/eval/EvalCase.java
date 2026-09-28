package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, versioned contract oracle for one evaluation execution. */
public record EvalCase(
        String caseId,
        String caseVersion,
        String category,
        List<AgentMessage> messages,
        AgentStopReason expectedStopReason,
        Set<String> allowedTools,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        int maxIterations,
        Long maxModelCalls,
        Long maxToolCalls
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
        Objects.requireNonNull(expectedStopReason, "expectedStopReason must not be null");
        allowedTools = immutableSet(allowedTools, "allowedTools");
        expectedTools = immutableSet(expectedTools, "expectedTools");
        forbiddenTools = immutableSet(forbiddenTools, "forbiddenTools");
        if (!allowedTools.containsAll(expectedTools)) {
            throw new IllegalArgumentException("expectedTools must be allowed");
        }
        if (!java.util.Collections.disjoint(expectedTools, forbiddenTools)) {
            throw new IllegalArgumentException("expectedTools and forbiddenTools must be disjoint");
        }
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
        requireOptionalNonNegative(maxModelCalls, "maxModelCalls");
        requireOptionalNonNegative(maxToolCalls, "maxToolCalls");
    }

    private static Set<String> immutableSet(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        values.forEach(value -> requireText(value, field + " entry"));
        return Set.copyOf(values);
    }

    private static void requireOptionalNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
