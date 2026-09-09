package com.multimodalAgent.agent.runtime;

public enum AgentStopReason {
    COMPLETED,
    TOOL_ERROR,
    POLICY_BLOCKED,
    WAITING_APPROVAL,
    MODEL_ERROR,
    MAX_ITERATIONS
}
