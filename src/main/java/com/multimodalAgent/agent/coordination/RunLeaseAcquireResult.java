package com.multimodalAgent.agent.coordination;

/**
 * Exhaustive, Redis-neutral result of trying to acquire ownership for a run.
 */
public sealed interface RunLeaseAcquireResult permits
        RunLeaseAcquireResult.Acquired,
        RunLeaseAcquireResult.AlreadyActive,
        RunLeaseAcquireResult.Unavailable {

    record Acquired(RunLease lease) implements RunLeaseAcquireResult {

        public Acquired {
            if (lease == null) {
                throw new IllegalArgumentException("lease must not be null");
            }
        }
    }

    record AlreadyActive(String runId) implements RunLeaseAcquireResult {

        public AlreadyActive {
            requireRunId(runId);
        }
    }

    record Unavailable(String runId) implements RunLeaseAcquireResult {

        public Unavailable {
            requireRunId(runId);
        }
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }
}
