package com.multimodalAgent.agent.stream;

import com.multimodalAgent.agent.runtime.control.ExecutionControlState;

import java.util.Objects;

/** Live observation of execution-control intent, not a Runtime transition. */
public record ControlEvent(ExecutionControlState state) implements ExecutionStreamPayload {

    public ControlEvent {
        Objects.requireNonNull(state, "state must not be null");
    }

    @Override
    public ExecutionStreamEventKind kind() {
        return ExecutionStreamEventKind.CONTROL_EVENT;
    }
}
