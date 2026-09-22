package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Durable ownership first, P8.4 local fast path second, optional P8.5 delivery last. */
@Service
public final class LocalRunCancellationService {

    private final AgentRunRepository runs;
    private final LocalExecutionControlRegistry controls;
    private final RemoteCancellationDispatcher remote;

    @Autowired
    public LocalRunCancellationService(
            AgentRunRepository runs,
            LocalExecutionControlRegistry controls,
            ObjectProvider<RemoteCancellationDispatcher> remoteProvider
    ) {
        this(runs, controls, remoteProvider.getIfAvailable());
    }

    LocalRunCancellationService(
            AgentRunRepository runs,
            LocalExecutionControlRegistry controls,
            RemoteCancellationDispatcher remote
    ) {
        this.runs = Objects.requireNonNull(runs, "runs must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.remote = remote;
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
            if (terminal(status)) {
                return CancelRequestResult.ALREADY_TERMINAL;
            }
            if (status == AgentRunStatus.WAITING_APPROVAL || remote == null) {
                return CancelRequestResult.NOT_ACTIVE;
            }
            Optional<CancelRequestResult> acknowledgment = remote.dispatch(runId);
            if (acknowledgment.isPresent()) {
                CancelRequestResult acknowledged = acknowledgment.orElseThrow();
                if (acknowledged == CancelRequestResult.NOT_ACTIVE) {
                    throw new DistributedCancellationUnavailableException(
                            "A non-owner cannot acknowledge cancellation for " + runId
                    );
                }
                return acknowledged;
            }
            // Publish alone is never acceptance. An ACK timeout is uncertain unless durable
            // terminal truth became visible while the command was in flight.
            AgentRunStatus afterTimeout = runs.findByRunIdAndUserId(runId, userId)
                    .orElse(run).getStatus();
            if (terminal(afterTimeout)) {
                return CancelRequestResult.ALREADY_TERMINAL;
            }
            throw new DistributedCancellationUnavailableException(
                    "Remote cancellation outcome is uncertain for " + runId
            );
        });
    }

    private boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED;
    }
}
