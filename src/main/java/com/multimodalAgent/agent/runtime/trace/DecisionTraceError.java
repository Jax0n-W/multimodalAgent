package com.multimodalAgent.agent.runtime.trace;

import java.util.Objects;

public record DecisionTraceError(
        long sequence,
        int iteration,
        DecisionTraceErrorSource source,
        String toolCallId,
        String toolName,
        String errorCode
) {

    public DecisionTraceError {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if (iteration < 0) {
            throw new IllegalArgumentException("iteration must not be negative");
        }
        Objects.requireNonNull(source, "source must not be null");
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
    }
}
