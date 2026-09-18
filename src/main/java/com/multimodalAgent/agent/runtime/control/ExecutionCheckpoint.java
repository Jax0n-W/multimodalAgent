package com.multimodalAgent.agent.runtime.control;

/**
 * Safe cooperative boundaries at which future Runtime work may observe cancellation intent.
 * P8.1 defines these values but does not connect them to the frozen Runtime Core.
 */
public enum ExecutionCheckpoint {
    BEFORE_MODEL,
    AFTER_MODEL,
    BEFORE_TOOL_EXECUTION,
    AFTER_TOOL_EXECUTION
}
