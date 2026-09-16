package com.multimodalAgent.agent.coordination;

/**
 * Redis-neutral result of a compare-and-renew operation.
 */
public enum RunLeaseRenewResult {
    RENEWED,
    EXPLICIT_LEASE_LOSS,
    COORDINATION_UNAVAILABLE
}
