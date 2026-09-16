package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Production composition root for the three persistence failure-boundary participants.
 * Every component produced here shares this root's single failure registry.
 */
@Component
public final class ExecutionPersistenceComposition {

    private final ExecutionHistoryStore store;
    private final ExecutionPersistenceFailureRegistry failures;
    private final ExecutionPersistenceEventPublisher eventPublisher;
    private final ExecutionPersistenceBoundaryMiddleware boundaryMiddleware;

    public ExecutionPersistenceComposition(ExecutionHistoryStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.failures = new ExecutionPersistenceFailureRegistry();
        this.eventPublisher = new ExecutionPersistenceEventPublisher(store, failures);
        this.boundaryMiddleware = new ExecutionPersistenceBoundaryMiddleware(failures);
    }

    public ExecutionPersistenceEventPublisher eventPublisher() {
        return eventPublisher;
    }

    public ExecutionPersistenceBoundaryMiddleware boundaryMiddleware() {
        return boundaryMiddleware;
    }

    public PersistentAgentExecutionCoordinator persistentCoordinator(
            AgentExecutionCoordinator delegate
    ) {
        return new PersistentAgentExecutionCoordinator(delegate, store, failures);
    }
}
