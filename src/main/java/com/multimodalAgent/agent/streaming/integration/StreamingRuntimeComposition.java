package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.recovery.RecoveryScanner;

import java.util.Objects;
import java.util.Optional;

/** Single composition root shared by fresh execution and the P10.6 resume path. */
public record StreamingRuntimeComposition(
        StreamingAgentExecutionService service,
        Optional<RecoveryScanner> recoveryScanner
) {
    public StreamingRuntimeComposition {
        Objects.requireNonNull(service, "service must not be null");
        Objects.requireNonNull(recoveryScanner, "recoveryScanner must not be null");
    }
}
