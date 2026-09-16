package com.multimodalAgent.agent.coordination;

/**
 * A former owner was explicitly proven not to own the run lease anymore.
 */
public final class RunLeaseLostException extends ExecutionCoordinationException {

    public RunLeaseLostException(String runId) {
        super(runId, "Run lease ownership was explicitly lost: " + runId);
    }
}
