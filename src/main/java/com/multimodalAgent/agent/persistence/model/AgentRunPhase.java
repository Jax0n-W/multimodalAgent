package com.multimodalAgent.agent.persistence.model;

public enum AgentRunPhase {
    RECEIVED,
    ROUTED,
    CONTEXT_BUILT,
    MODEL_RUNNING,
    AWAITING_TOOL,
    TOOL_RUNNING,
    GENERATING,
    FINALIZING
}
