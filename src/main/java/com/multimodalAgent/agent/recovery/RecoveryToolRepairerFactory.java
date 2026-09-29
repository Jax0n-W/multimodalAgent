package com.multimodalAgent.agent.recovery;

@FunctionalInterface
public interface RecoveryToolRepairerFactory {
    RecoveryToolRepairer create(RecoveryAuthorityGuard authorityGuard);
}
