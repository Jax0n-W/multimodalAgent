package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;
import java.util.Optional;

public record RecoveryEngineResult(Status status, Optional<AgentRunResult> runResult) {
    public RecoveryEngineResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(runResult, "runResult must not be null");
        if ((status == Status.RESUMED) != runResult.isPresent()) {
            throw new IllegalArgumentException("Only RESUMED carries a run result");
        }
    }

    public static RecoveryEngineResult of(Status status) {
        return new RecoveryEngineResult(status, Optional.empty());
    }

    public enum Status {
        RESUMED,
        ALREADY_ACTIVE,
        NOT_RESUMABLE,
        MANUAL_INTERVENTION
    }
}
