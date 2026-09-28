package com.multimodalAgent.agent.recovery;

public final class RecoveryCheckpointConflictException extends RecoveryCheckpointException {

    public RecoveryCheckpointConflictException(String checkpointId) {
        super("Conflicting immutable recovery checkpoint: " + checkpointId);
    }
}
