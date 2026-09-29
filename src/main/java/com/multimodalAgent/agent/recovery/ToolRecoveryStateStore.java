package com.multimodalAgent.agent.recovery;

public interface ToolRecoveryStateStore {

    ToolUnknownMaterializationResult materializeUnknown(String runId, String toolCallId);
}
