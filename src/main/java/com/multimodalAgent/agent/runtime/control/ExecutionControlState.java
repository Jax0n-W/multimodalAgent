package com.multimodalAgent.agent.runtime.control;

/**
 * Monotonic control intent for one execution.
 *
 * <p>This state does not describe lease ownership or the terminal Runtime result.</p>
 */
public enum ExecutionControlState {
    RUNNING,
    CANCEL_REQUESTED
}
