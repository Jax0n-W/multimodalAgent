package com.multimodalAgent.agent.runtime.tool;

/**
 * A tool that needs execution identity supplied by the runtime rather than model arguments.
 * User/session/snapshot identities must never be accepted from model-generated JSON.
 */
public interface ContextAwareAgentTool<I, O> extends AgentTool<I, O> {

    O execute(I input, ToolExecutionContext context);

    @Override
    default O execute(I input) {
        throw new IllegalStateException("Context-aware tools must be invoked by ToolExecutor");
    }
}
