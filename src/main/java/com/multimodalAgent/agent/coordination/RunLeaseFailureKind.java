package com.multimodalAgent.agent.coordination;

/**
 * Why an execution can no longer prove active ownership.
 */
public enum RunLeaseFailureKind {
    EXPLICIT_LEASE_LOSS,
    COORDINATION_UNAVAILABLE
}
