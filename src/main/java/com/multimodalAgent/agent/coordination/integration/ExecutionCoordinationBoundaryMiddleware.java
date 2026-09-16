package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.harness.AgentRuntimeContextContributor;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributeKey;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.Objects;

/**
 * Cooperative fencing at Model and Tool safe boundaries.
 *
 * <p>A started Core operation is allowed to establish its truthful terminal fact. The post-check
 * then prevents any future operation when ownership was lost in flight.</p>
 */
public final class ExecutionCoordinationBoundaryMiddleware implements RuntimeMiddleware {

    public static final int ORDER = 100;
    private static final RuntimeAttributeKey<RunLeaseSession> RUN_LEASE_SESSION =
            RuntimeAttributeKey.of(
                    "coordination.runLeaseSession",
                    RunLeaseSession.class
            );

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> next
    ) {
        RunLeaseSession session = requireSession(context);
        session.assertExecutionAuthority();
        ModelTurn turn = next.proceed();
        session.assertExecutionAuthority();
        return turn;
    }

    @Override
    public ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> next
    ) {
        RunLeaseSession session = requireSession(context);
        session.assertExecutionAuthority();
        ToolResult result = next.proceed();
        session.assertExecutionAuthority();
        return result;
    }

    public static AgentRuntimeContextContributor sessionContributor(RunLeaseSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return context -> {
            if (!context.runId().equals(session.lease().runId())) {
                throw new IllegalArgumentException(
                        "Run lease session identity must match Runtime context"
                );
            }
            if (context.attributes().put(RUN_LEASE_SESSION, session).isPresent()) {
                throw new IllegalStateException(
                        "Run lease session is already bound to Runtime context"
                );
            }
        };
    }

    private RunLeaseSession requireSession(AgentRuntimeContext context) {
        return context.attributes().get(RUN_LEASE_SESSION).orElseThrow(() ->
                new CoordinationUnavailableException(
                        context.runId(),
                        new IllegalStateException(
                                "Run lease session is missing from Runtime context"
                        )
                )
        );
    }
}
