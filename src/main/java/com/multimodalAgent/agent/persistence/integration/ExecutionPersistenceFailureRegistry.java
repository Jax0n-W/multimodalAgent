package com.multimodalAgent.agent.persistence.integration;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Correlates the first synchronous persistence failure with its active run.
 */
public final class ExecutionPersistenceFailureRegistry {

    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ExecutionPersistenceException> failures =
            new ConcurrentHashMap<>();

    public void open(String runId) {
        if (!activeRuns.add(runId)) {
            throw new ExecutionPersistenceException(
                    "Persistence session is already active for run: " + runId
            );
        }
    }

    public boolean isActive(String runId) {
        return activeRuns.contains(runId);
    }

    public void recordFailure(String runId, ExecutionPersistenceException failure) {
        if (isActive(runId)) {
            failures.putIfAbsent(runId, failure);
        }
    }

    public Optional<ExecutionPersistenceException> failure(String runId) {
        return Optional.ofNullable(failures.get(runId));
    }

    public void throwIfFailed(String runId) {
        failure(runId).ifPresent(failure -> {
            throw failure;
        });
    }

    public void close(String runId) {
        failures.remove(runId);
        activeRuns.remove(runId);
    }
}
