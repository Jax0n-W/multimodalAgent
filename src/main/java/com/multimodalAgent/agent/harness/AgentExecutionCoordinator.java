package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;

import java.util.Objects;

/**
 * Application-level boundary that creates one run context and surrounds the Agent core with
 * {@code aroundRun} middleware.
 *
 * <p>Agent events describe core lifecycle facts. If middleware fails after the runner has emitted
 * {@code RUN_COMPLETED}, this coordinator propagates the middleware-origin failure without
 * rewriting the completed core outcome or emitting another terminal event.</p>
 */
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
