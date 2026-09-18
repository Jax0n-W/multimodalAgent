package com.multimodalAgent.agent.stream;

/**
 * Closed, type-safe payload hierarchy for live execution observations.
 */
public sealed interface ExecutionStreamPayload permits
        RuntimeEventPayload,
        ModelDelta,
        ControlEvent {

    ExecutionStreamEventKind kind();
}
