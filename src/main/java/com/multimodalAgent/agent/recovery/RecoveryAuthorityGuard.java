package com.multimodalAgent.agent.recovery;

/**
 * Verifies recovery authority already established by a higher-level coordinator.
 * This seam never acquires ownership. Failure requires callers to fail closed and
 * forbids subsequent recovery mutation until authority is established again.
 */
@FunctionalInterface
public interface RecoveryAuthorityGuard {

    void assertAuthority(String runId);
}
