package com.multimodalAgent.agent.runtime.tool;

/** Signals that Tool execution returned but its model-visible outcome could not be serialized. */
public final class ToolOutcomeSerializationException extends RuntimeException {

    public ToolOutcomeSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
