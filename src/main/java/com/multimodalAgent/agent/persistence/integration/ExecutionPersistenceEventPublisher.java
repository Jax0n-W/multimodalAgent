package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;

import java.util.Objects;

/**
 * Synchronously projects Core events and defers a failed write to the next safe boundary.
 */
public final class ExecutionPersistenceEventPublisher implements AgentEventPublisher {

    private final ExecutionHistoryStore store;
    private final ExecutionPersistenceFailureRegistry failures;

    public ExecutionPersistenceEventPublisher(
            ExecutionHistoryStore store,
            ExecutionPersistenceFailureRegistry failures
    ) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
    }

    @Override
    public void publish(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!failures.isActive(event.runId()) || failures.failure(event.runId()).isPresent()) {
            return;
        }
        try {
            store.record(event);
        } catch (RuntimeException exception) {
            failures.recordFailure(
                    event.runId(),
                    new ExecutionPersistenceException(
                            "Could not persist " + event.type() + " for run " + event.runId(),
                            exception
                    )
            );
        }
    }
}
