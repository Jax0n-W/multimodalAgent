package com.multimodalAgent.agent.runtime.model.gateway;

public enum ModelFailureKind {
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    INVALID_REQUEST,
    CONTEXT_TOO_LARGE,
    MALFORMED_RESPONSE,
    PROVIDER_ERROR
}
