package com.multimodalAgent.agent.recovery;

public enum ToolAmbiguityAction {
    RECONCILE,
    REPLAY_DEFERRED,
    IDEMPOTENT_RETRY_DEFERRED,
    MANUAL_INTERVENTION,
    CONTRACT_UNAVAILABLE
}
