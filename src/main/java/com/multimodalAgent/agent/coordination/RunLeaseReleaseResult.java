package com.multimodalAgent.agent.coordination;

/**
 * Redis-neutral result of a compare-and-release operation.
 */
public enum RunLeaseReleaseResult {
    RELEASED,
    NO_LONGER_OWNER,
    COORDINATION_UNAVAILABLE
}
