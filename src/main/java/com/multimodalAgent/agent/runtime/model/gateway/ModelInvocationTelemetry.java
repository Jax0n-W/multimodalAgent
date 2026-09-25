package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.TokenUsage;

import java.time.Duration;
import java.util.Objects;

public record ModelInvocationTelemetry(
        String invocationId,
        ModelIdentity identity,
        int iteration,
        Duration latency,
        ModelFinishReason finishReason,
        TokenUsage tokenUsage,
        ModelFailureKind failureKind
) {

    public ModelInvocationTelemetry {
        if (invocationId == null || invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
        Objects.requireNonNull(identity, "identity must not be null");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        Objects.requireNonNull(latency, "latency must not be null");
        Objects.requireNonNull(tokenUsage, "tokenUsage must not be null");
        if ((failureKind == null) == (finishReason == null)) {
            throw new IllegalArgumentException(
                    "Telemetry must describe exactly one success or failure outcome"
            );
        }
    }

    public boolean succeeded() {
        return failureKind == null;
    }
}
