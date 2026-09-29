package com.multimodalAgent.agent.runtime.tool;

/** Signals that external tool work completed but its exact outcome could not be made durable. */
public final class ToolOutcomeRecordingException extends RuntimeException {

    public ToolOutcomeRecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
