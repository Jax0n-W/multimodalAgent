package com.multimodalAgent.agent.runtime;

public enum AgentStopReason {
    COMPLETED,
    TOOL_ERROR,
    POLICY_BLOCKED,
    WAITING_APPROVAL,
    MODEL_ERROR,
    MODEL_TIMEOUT,
    MAX_ITERATIONS,
    CANCELLED,
    INTERNAL_ERROR
}
