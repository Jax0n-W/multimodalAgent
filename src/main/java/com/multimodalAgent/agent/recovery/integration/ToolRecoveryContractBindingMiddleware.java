package com.multimodalAgent.agent.recovery.integration;

import com.multimodalAgent.agent.recovery.ToolRecoveryContractBindingException;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractBindingStore;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Commits the execution-time contract before TOOL_STARTED or external execution is possible. */
@Component
public final class ToolRecoveryContractBindingMiddleware implements RuntimeMiddleware {

    public static final int ORDER = 175;

    private final ToolRecoveryContractBindingStore store;

    public ToolRecoveryContractBindingMiddleware(ToolRecoveryContractBindingStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ToolResult aroundToolExecution(
            AgentRuntimeContext context,
            ToolExecutionMetadata metadata,
            RuntimeInvocation<ToolResult> next
    ) {
        ToolRecoveryContractSnapshot contract = metadata.recoveryContract()
                .orElseThrow(() -> new ToolRecoveryContractBindingException(
                        "Execution-time tool recovery contract is unavailable"
                ));
        store.bind(context.runId(), metadata.toolCallId(), contract);
        return next.proceed();
    }
}
