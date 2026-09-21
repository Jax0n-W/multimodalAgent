package com.multimodalAgent.agent.runtime.control;

/**
 * Safe cooperative boundaries at which future Runtime work may observe cancellation intent.
 * P8.4 observes these values at the Model and Tool execution boundaries.
 */
public enum ExecutionCheckpoint {
    BEFORE_MODEL,
    AFTER_MODEL,
    BEFORE_TOOL_EXECUTION,
    AFTER_TOOL_EXECUTION
}
