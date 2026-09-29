package com.multimodalAgent.agent.recovery;

public enum ToolReconciliationOutcome {
    CONFIRMED_APPLIED,
    CONFIRMED_NOT_APPLIED,
    UNRESOLVED,
    MANUAL_INTERVENTION_REQUIRED;

    public boolean decisive() {
        return this != UNRESOLVED;
    }
}
