package com.multimodalAgent.agent.runtime.tool;

/**
 * Runtime-neutral boundary that makes an exact, model-visible successful tool outcome durable.
 * Implementations must return only after the outcome is durable.
 */
@FunctionalInterface
public interface ToolOutcomeRecorder {

    ToolOutcomeRecorder NOOP = (runId, toolCallId, toolName, modelVisibleResult) -> {
    };

    void recordSuccess(
            String runId,
            String toolCallId,
            String toolName,
            String modelVisibleResult
    );
}
