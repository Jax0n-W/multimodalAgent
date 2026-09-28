package com.multimodalAgent.agent.recovery;

public final class RecoveryCheckpointCorruptionException extends RecoveryCheckpointException {

    public RecoveryCheckpointCorruptionException(String message) {
        super(message);
    }

    public RecoveryCheckpointCorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
