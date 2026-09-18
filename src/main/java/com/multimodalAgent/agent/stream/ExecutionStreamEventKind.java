package com.multimodalAgent.agent.stream;

/** Types of observations that may be interleaved in one live execution stream. */
public enum ExecutionStreamEventKind {
    RUNTIME_EVENT,
    MODEL_DELTA,
    CONTROL_EVENT
}
