package com.multimodalAgent.agent.coordination;

/**
 * Harness-level coordination failure. It is deliberately not an AgentStopReason.
 */
public class ExecutionCoordinationException extends RuntimeException {

    private final String runId;

    public ExecutionCoordinationException(String runId, String message) {
        super(message);
        this.runId = requireRunId(runId);
    }

    public ExecutionCoordinationException(String runId, String message, Throwable cause) {
        super(message, cause);
        this.runId = requireRunId(runId);
    }

    public String runId() {
        return runId;
    }

    private static String requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        return runId;
    }
}
