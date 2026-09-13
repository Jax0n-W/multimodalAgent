package com.multimodalAgent.agent.runtime.extension;

/**
 * Base exception type used by the runtime extension API.
 *
 * <p>The exception type alone does not prove that a failure originated in middleware: a
 * downstream model or tool may also throw this type. The extension kernel uses
 * {@link RuntimeMiddlewareFailureException} to mark failures that it has positively identified
 * as middleware-originated.</p>
 */
public class RuntimeMiddlewareException extends RuntimeException {

    public RuntimeMiddlewareException(String message) {
        super(message);
    }

    public RuntimeMiddlewareException(String message, Throwable cause) {
        super(message, cause);
    }
}
