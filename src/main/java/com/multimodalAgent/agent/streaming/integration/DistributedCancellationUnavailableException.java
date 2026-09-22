package com.multimodalAgent.agent.streaming.integration;

/** Caller-visible failure to determine a remote cancellation outcome. */
public final class DistributedCancellationUnavailableException extends RuntimeException {

    public DistributedCancellationUnavailableException(String message) {
        super(message);
    }

    public DistributedCancellationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
