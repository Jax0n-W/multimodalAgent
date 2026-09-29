package com.multimodalAgent.agent.recovery;

public final class ToolReconcilerUnavailableException extends RuntimeException {

    public ToolReconcilerUnavailableException(String strategyId) {
        super("No ToolReconciler is registered for strategy " + strategyId);
    }
}
