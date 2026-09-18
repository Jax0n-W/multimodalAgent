package com.multimodalAgent.agent.stream;

import com.multimodalAgent.agent.runtime.event.AgentEvent;

import java.util.Objects;

/** Wraps an already-established Runtime fact without copying or changing it. */
public record RuntimeEventPayload(AgentEvent event) implements ExecutionStreamPayload {

    public RuntimeEventPayload {
        Objects.requireNonNull(event, "event must not be null");
    }

    @Override
    public ExecutionStreamEventKind kind() {
        return ExecutionStreamEventKind.RUNTIME_EVENT;
    }
}
