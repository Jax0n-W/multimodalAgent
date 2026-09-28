package com.multimodalAgent.agent.recovery.integration;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.budget.BudgetRuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributeKey;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.util.Objects;
import java.util.function.Consumer;

/** Captures exact Runtime results only after their terminal P6 facts have become durable. */
public final class RecoveryCheckpointRuntimeMiddleware implements RuntimeMiddleware {

    public static final int ORDER = 150;
    static final RuntimeAttributeKey<RecoveryCheckpointSession> SESSION =
            RuntimeAttributeKey.of(
                    "recovery.checkpoint.session",
                    RecoveryCheckpointSession.class
            );
    private final Consumer<String> durableHistoryGuard;

    public RecoveryCheckpointRuntimeMiddleware() {
        this(runId -> {
        });
    }

    public RecoveryCheckpointRuntimeMiddleware(Consumer<String> durableHistoryGuard) {
        this.durableHistoryGuard = Objects.requireNonNull(
                durableHistoryGuard,
                "durableHistoryGuard must not be null"
        );
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public AgentRunResult aroundRun(
            AgentRuntimeContext context,
            RuntimeInvocation<AgentRunResult> next
    ) {
        RecoveryCheckpointSession session = requireSession(context);
        AgentRunResult result = next.proceed();
        durableHistoryGuard.accept(context.runId());
        session.afterRun(
                result,
                context.attributes().get(BudgetRuntimeAttributes.USAGE).orElseThrow(() ->
                        new IllegalStateException("Runtime budget usage was not published")
                )
        );
        return result;
    }

    @Override
    public ModelTurn aroundModelCall(
            AgentRuntimeContext context,
            ModelCallMetadata metadata,
            RuntimeInvocation<ModelTurn> next
    ) {
        RecoveryCheckpointSession session = requireSession(context);
        durableHistoryGuard.accept(context.runId());
        if (metadata.iteration() > 1) {
            session.iterationBoundary(metadata.iteration() - 1);
        }
        ModelTurn turn = next.proceed();
        durableHistoryGuard.accept(context.runId());
        session.afterModelOutcome(metadata.iteration(), turn);
        return turn;
    }

    @Override
    public ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> next
    ) {
        RecoveryCheckpointSession session = requireSession(context);
        durableHistoryGuard.accept(context.runId());
        ToolResult result = next.proceed();
        durableHistoryGuard.accept(context.runId());
        session.afterToolOutcome(
                metadata.iteration(), metadata.toolCallId(), metadata.toolName(), result
        );
        return result;
    }

    private RecoveryCheckpointSession requireSession(AgentRuntimeContext context) {
        return context.attributes().get(SESSION).orElseThrow(() ->
                new IllegalStateException("Recovery checkpoint session is not installed")
        );
    }
}
