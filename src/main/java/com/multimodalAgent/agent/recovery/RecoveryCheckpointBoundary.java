package com.multimodalAgent.agent.recovery;

/** Confirmed execution boundaries whose continuation state is safe to persist. */
public enum RecoveryCheckpointBoundary {
    AFTER_MODEL_OUTCOME,
    AFTER_TOOL_OUTCOME,
    ITERATION_BOUNDARY,
    WAITING_APPROVAL
}
