package com.multimodalAgent.agent.coordination;

/**
 * Lease acquisition was rejected because the run already has an active owner.
 */
public final class RunAlreadyActiveException extends ExecutionCoordinationException {

    public RunAlreadyActiveException(String runId) {
        super(runId, "Run already has an active execution owner: " + runId);
    }
}
