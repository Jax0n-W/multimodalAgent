package com.multimodalAgent.agent.recovery;

public interface ToolRecoveryContractBindingStore {

    void bind(String runId, String toolCallId, ToolRecoveryContractSnapshot contract);
}
