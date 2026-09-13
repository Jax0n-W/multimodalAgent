package com.multimodalAgent.agent.runtime.extension;

public final class RuntimeMiddlewareException extends RuntimeException {

    public RuntimeMiddlewareException(String message) {
        super(message);
    }

    public RuntimeMiddlewareException(String message, Throwable cause) {
        super(message, cause);
    }
}
