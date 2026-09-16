package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;

/**
 * Durable admission/finalization boundary around the existing execution coordinator.
 */
public final class PersistentAgentExecutionCoordinator {

    private final AgentExecutionCoordinator delegate;
    private final ExecutionHistoryStore store;
    private final ExecutionPersistenceFailureRegistry failures;

    PersistentAgentExecutionCoordinator(
            AgentExecutionCoordinator delegate,
            ExecutionHistoryStore store,
            ExecutionPersistenceFailureRegistry failures
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String runId = request.runSpec().runId();
        failures.open(runId);
        try {
            admit(request);
            AgentRunResult result = delegate.execute(request);
            failures.throwIfFailed(runId);
            finalizeRun(runId, result);
            return result;
        } catch (RuntimeException exception) {
            ExecutionPersistenceException persistenceFailure = failures.failure(runId)
                    .orElse(null);
            if (persistenceFailure != null) {
                throw persistenceFailure;
            }
            throw exception;
        } finally {
            failures.close(runId);
        }
    }

    private void admit(AgentExecutionRequest request) {
        if (request.requestId() == null || request.userId() == null) {
            throw new ExecutionPersistenceException(
                    "Durable execution requires non-null requestId and userId"
            );
        }
        try {
            store.admit(request);
        } catch (ExecutionPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExecutionPersistenceException(
                    "Could not create durable AgentRun " + request.runSpec().runId(),
                    exception
            );
        }
    }

    private void finalizeRun(String runId, AgentRunResult result) {
        try {
            store.finalizeRun(runId, result);
        } catch (ExecutionPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExecutionPersistenceException(
                    "Could not finalize durable AgentRun " + runId,
                    exception
            );
        }
    }
}
