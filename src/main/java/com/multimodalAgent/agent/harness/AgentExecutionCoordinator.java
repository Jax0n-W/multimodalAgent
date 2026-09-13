package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;

import java.util.Objects;

public final class AgentExecutionCoordinator {

    private final AgentRunner agentRunner;
    private final RuntimeMiddlewareChain middlewareChain;

    public AgentExecutionCoordinator(
            AgentRunner agentRunner,
            RuntimeMiddlewareChain middlewareChain
    ) {
        this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner must not be null");
        this.middlewareChain = Objects.requireNonNull(
                middlewareChain,
                "middlewareChain must not be null"
        );
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        AgentRunSpec spec = request.runSpec();
        AgentRuntimeContext context = new AgentRuntimeContext(
                spec.runId(),
                request.requestId(),
                spec.sessionId(),
                request.userId(),
                request.runtimeConfigSnapshotId(),
                request.cancellationContext(),
                new RuntimeAttributes()
        );
        return middlewareChain.aroundRun(
                context,
                () -> agentRunner.run(spec, context, middlewareChain)
        );
    }
}
