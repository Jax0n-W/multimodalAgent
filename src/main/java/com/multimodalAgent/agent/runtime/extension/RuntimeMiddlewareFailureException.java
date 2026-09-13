package com.multimodalAgent.agent.runtime.extension;

/**
 * Marks a failure that the extension kernel has identified as originating from middleware.
 * Downstream model and tool failures are never converted to this type.
 */
public final class RuntimeMiddlewareFailureException extends RuntimeMiddlewareException {

    RuntimeMiddlewareFailureException(String message) {
        super(message);
    }

    RuntimeMiddlewareFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
