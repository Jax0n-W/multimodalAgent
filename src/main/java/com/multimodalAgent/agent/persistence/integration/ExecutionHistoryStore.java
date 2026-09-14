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
}
