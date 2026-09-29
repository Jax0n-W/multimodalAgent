package com.multimodalAgent.agent.recovery;

public final class ReliableToolOutcomeConflictException extends RuntimeException {

    public ReliableToolOutcomeConflictException(String executionId) {
        super("Conflicting reliable tool outcome for execution " + executionId);
    }
}
