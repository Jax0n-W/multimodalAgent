package com.multimodalAgent.agent.coordination;

/**
 * Coordination infrastructure could not prove that ownership is still valid.
 */
public final class CoordinationUnavailableException extends ExecutionCoordinationException {

    public CoordinationUnavailableException(String runId) {
        super(runId, "Coordination infrastructure could not confirm ownership: " + runId);
    }

    public CoordinationUnavailableException(String runId, Throwable cause) {
        super(runId, "Coordination infrastructure could not confirm ownership: " + runId, cause);
    }
}
