package com.multimodalAgent.agent.eval;

import java.util.Objects;
import java.util.Set;

/** Inputs that configure the existing Runtime execution for one Eval case. */
public record EvalExecutionConfig(
        int maxIterations,
        Set<String> allowedTools,
        EvalExecutionBudget budget
) {

    public EvalExecutionConfig {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1");
        }
        allowedTools = immutableToolSet(allowedTools, "allowedTools");
        Objects.requireNonNull(budget, "budget must not be null");
    }

    static Set<String> immutableToolSet(Set<String> values, String field) {
        Objects.requireNonNull(values, field + " must not be null");
        values.forEach(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " entries must not be blank");
            }
        });
        return Set.copyOf(values);
    }
}
