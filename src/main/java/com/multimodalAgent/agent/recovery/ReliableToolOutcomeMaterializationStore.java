package com.multimodalAgent.agent.recovery;

public interface ReliableToolOutcomeMaterializationStore {

    ReliableToolOutcomeMaterialization materialize(String runId, String toolCallId);
}
