package com.multimodalAgent.agent.runtime.control;

import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;

import java.util.Objects;

/** Cooperative checkpoint and terminal-decision operations used only by Runtime Core. */
public final class RuntimeCancellation {

    private RuntimeCancellation() {
    }

    public static boolean requested(
            AgentRuntimeContext context,
            ExecutionCheckpoint checkpoint
    ) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        return context.cancellationContext().isCancellationRequested();
    }

    public static boolean trySealNormalCompletion(AgentRuntimeContext context) {
        CancellationContext cancellation = context.cancellationContext();
        return cancellation instanceof RuntimeCancellationBoundary boundary
                ? boundary.trySealNormalCompletion()
                : !cancellation.isCancellationRequested();
    }

    public static void sealCoreTerminal(AgentRuntimeContext context) {
        if (context.cancellationContext() instanceof RuntimeCancellationBoundary boundary) {
            boundary.sealCoreTerminal();
        }
    }
}
