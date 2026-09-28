package com.multimodalAgent.agent.recovery;

public final class RecoveryCheckpointSequenceException extends RecoveryCheckpointException {

    public RecoveryCheckpointSequenceException(
            String runId,
            long checkpointSequence,
            long latestDurableSequence
    ) {
        super("Recovery checkpoint sequence does not advance durable history for run "
                + runId + ": " + checkpointSequence + " <= " + latestDurableSequence);
    }
}
