package com.multimodalAgent.agent.runtime.model.gateway;

import java.time.Duration;
import java.util.Objects;

public record ModelTimeoutPolicy(Duration invocationTimeout, Duration idleTimeout) {

    public ModelTimeoutPolicy {
        requirePositive(invocationTimeout, "invocationTimeout");
        requirePositive(idleTimeout, "idleTimeout");
        if (idleTimeout.compareTo(invocationTimeout) > 0) {
            throw new IllegalArgumentException("idleTimeout must not exceed invocationTimeout");
        }
    }

    private static void requirePositive(Duration value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
