package com.multimodalAgent.agent.recovery;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record RecoveryRepairResult(Status status, Set<String> retryToolCallIds) {
    public RecoveryRepairResult {
        Objects.requireNonNull(status, "status must not be null");
        retryToolCallIds = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(retryToolCallIds, "retryToolCallIds must not be null")
        ));
    }

    public enum Status { REEVALUATE, RETRY_AUTHORIZED, MANUAL }
}
