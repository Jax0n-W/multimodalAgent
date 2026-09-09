package com.multimodalAgent.agent.persistence.model;

public enum AgentRunPhase {
    RECEIVED,
    MODEL_RUNNING,
    TOOL_RUNNING,
    WAITING_APPROVAL,
    FINALIZING,
    COMPLETED,
    FAILED
}
