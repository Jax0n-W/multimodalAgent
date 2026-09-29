package com.multimodalAgent.agent.recovery;

/** Verifies authority already acquired by a higher recovery coordinator. */
@FunctionalInterface
public interface RecoveryAuthorityGuard {

    void assertAuthority(String runId);
}
