package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Authorizes against durable ownership, then addresses only this JVM's active execution. */
@Service
public final class LocalRunCancellationService {

    private final AgentRunRepository runs;
    private final LocalExecutionControlRegistry controls;

    public LocalRunCancellationService(
            AgentRunRepository runs,
            LocalExecutionControlRegistry controls
    ) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
    }

    /** Empty means unknown or not owned; neither condition is disclosed to the caller. */
    public Optional<CancelRequestResult> cancel(String runId, Long userId) {
        return runs.findByRunIdAndUserId(runId, userId).map(run -> {
            CancelRequestResult result = controls.requestCancel(runId);
            if (result != CancelRequestResult.NOT_ACTIVE) {
                return result;
            }
            // The entry may have closed after the first DB read. Re-read terminal status.
            AgentRunStatus status = runs.findByRunIdAndUserId(runId, userId)
                    .orElse(run).getStatus();
            return status == AgentRunStatus.COMPLETED
                    || status == AgentRunStatus.FAILED
                    || status == AgentRunStatus.CANCELLED
                    ? CancelRequestResult.ALREADY_TERMINAL
                    : CancelRequestResult.NOT_ACTIVE;
        });
    }
}
