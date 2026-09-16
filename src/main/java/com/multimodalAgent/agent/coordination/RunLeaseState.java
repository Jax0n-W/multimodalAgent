package com.multimodalAgent.agent.coordination;

/**
 * Execution-scoped ownership lifecycle. There is intentionally no transition back to ACTIVE.
 */
public enum RunLeaseState {
    ACTIVE,
    LOST,
    CLOSING,
    CLOSED
}
