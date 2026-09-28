package com.multimodalAgent.agent.execution.config;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;

import java.util.ArrayList;
import java.util.Objects;

/** Resolves snapshots only from objects that the execution path actually consumes. */
public final class ResolvedExecutionConfigResolver {

    private final ResolvedModelConfig modelConfig;

    public ResolvedExecutionConfigResolver(ResolvedModelConfig modelConfig) {
        this.modelConfig = Objects.requireNonNull(modelConfig, "modelConfig must not be null");
    }

    public ResolvedExecutionConfig resolve(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRunSpec spec = request.runSpec();
        return new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                modelConfig,
                new ResolvedExecutionConfig.RuntimeConfig(
                        spec.maxIterations(),
                        new ArrayList<>(spec.allowedTools())
                ),
                spec.budget()
        );
    }
}
