package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentStopReason;

import java.util.Objects;
import java.util.Set;

/** Expected observations used for deterministic contract evaluation only. */
public record EvalOracle(
        AgentStopReason expectedStopReason,
        Set<String> expectedTools,
        Set<String> forbiddenTools,
        Long maxExpectedModelCalls,
        Long maxExpectedToolCalls
) {

    public EvalOracle {
        Objects.requireNonNull(expectedStopReason, "expectedStopReason must not be null");
        expectedTools = EvalExecutionConfig.immutableToolSet(expectedTools, "expectedTools");
        forbiddenTools = EvalExecutionConfig.immutableToolSet(
                forbiddenTools, "forbiddenTools"
        );
        if (!java.util.Collections.disjoint(expectedTools, forbiddenTools)) {
            throw new IllegalArgumentException(
                    "expectedTools and forbiddenTools must be disjoint"
            );
        }
        requireNonNegative(maxExpectedModelCalls, "maxExpectedModelCalls");
        requireNonNegative(maxExpectedToolCalls, "maxExpectedToolCalls");
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
