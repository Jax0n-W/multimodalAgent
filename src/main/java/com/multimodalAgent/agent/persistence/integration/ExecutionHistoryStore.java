package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.event.AgentEvent;

/**
 * Infrastructure boundary for durable projections of already-formed runtime facts.
 */
public interface ExecutionHistoryStore {

    void admit(AgentExecutionRequest request);

    void record(AgentEvent event);

    void finalizeRun(String runId, AgentRunResult result);

    /** Validates identity/status before an existing-run resume; it must not create durable data. */
    default void assertExistingRunning(String runId, String runtimeConfigSnapshotId) {
        throw new UnsupportedOperationException("Existing-run resume is not supported");
    }
}
