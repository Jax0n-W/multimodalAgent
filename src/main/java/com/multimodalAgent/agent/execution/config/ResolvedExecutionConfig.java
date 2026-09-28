package com.multimodalAgent.agent.execution.config;

import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;

import java.util.List;
import java.util.Objects;

/** Complete effective configuration used by one execution, excluding credentials and secrets. */
public record ResolvedExecutionConfig(
        int schemaVersion,
        ResolvedModelConfig model,
        RuntimeConfig runtime,
        ExecutionBudget budget
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ResolvedExecutionConfig {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be at least 1");
        }
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(budget, "budget must not be null");
    }

    public record RuntimeConfig(int maxIterations, List<String> allowedTools) {

        public RuntimeConfig {
            if (maxIterations < 1) {
                throw new IllegalArgumentException("maxIterations must be at least 1");
            }
            Objects.requireNonNull(allowedTools, "allowedTools must not be null");
            allowedTools = allowedTools.stream()
                    .map(tool -> Objects.requireNonNull(tool, "allowedTools must not contain null"))
                    .peek(tool -> {
                        if (tool.isBlank()) {
                            throw new IllegalArgumentException("allowedTools must not contain blank names");
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
        }
    }
}
