package com.multimodalAgent.agent.persistence.integration;

/**
 * Boundary-level infrastructure failure. It is deliberately not an AgentStopReason.
 */
public final class ExecutionPersistenceException extends RuntimeException {

    public ExecutionPersistenceException(String message) {
        super(message);
    }

    public ExecutionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
