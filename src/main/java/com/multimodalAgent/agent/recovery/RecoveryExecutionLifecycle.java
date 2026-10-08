package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;

/** Application port for one authorized existing-run execution segment. */
@FunctionalInterface
public interface RecoveryExecutionLifecycle {

    AgentRunResult resume(RecoveryExecutionRequest request);
}
