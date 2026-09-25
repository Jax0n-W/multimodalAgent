package com.multimodalAgent.agent.runtime.model.gateway;

import java.util.Objects;

/** Typed adapter failure; providers must not be classified by parsing exception messages. */
public class ModelProviderException extends RuntimeException {

    private final ModelFailureKind failureKind;

    public ModelProviderException(ModelFailureKind failureKind, String message) {
        super(message);
        this.failureKind = Objects.requireNonNull(failureKind, "failureKind must not be null");
    }

    public ModelProviderException(
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
