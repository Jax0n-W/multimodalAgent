package com.multimodalAgent.agent.runtime.model.gateway;

import java.util.Objects;

/** Stable Gateway failure exposed to Runtime Core. */
public final class ModelInvocationException extends RuntimeException {

    private final ModelFailureKind failureKind;

    public ModelInvocationException(
            ModelFailureKind failureKind,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
    }

    public ModelFailureKind failureKind() {
        return failureKind;
    }
}
