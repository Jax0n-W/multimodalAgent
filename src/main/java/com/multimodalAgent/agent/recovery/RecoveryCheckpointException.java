package com.multimodalAgent.agent.recovery;

public class RecoveryCheckpointException extends RuntimeException {

    public RecoveryCheckpointException(String message) {
        super(message);
    }

    public RecoveryCheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
