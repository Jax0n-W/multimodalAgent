package com.multimodalAgent.agent.stream;

import com.multimodalAgent.agent.runtime.event.AgentEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable envelope for one live execution observation.
 *
 * <p>{@code streamSequence} orders all live observations for one run and is independent from
 * {@link AgentEvent#sequence()}, which orders Runtime Core facts only.</p>
 */
public record ExecutionStreamEvent(
        String runId,
        long streamSequence,
        Instant occurredAt,
        ExecutionStreamEventKind kind,
        ExecutionStreamPayload payload
) {

    public static final long INITIAL_SEQUENCE = 1L;

    public ExecutionStreamEvent {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (streamSequence < INITIAL_SEQUENCE) {
            throw new IllegalArgumentException("streamSequence must be at least 1");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (kind != payload.kind()) {
            throw new IllegalArgumentException("kind must match payload kind");
        }
        if (payload instanceof RuntimeEventPayload runtimePayload
                && !runId.equals(runtimePayload.event().runId())) {
            throw new IllegalArgumentException("runId must match wrapped Runtime event");
        }
    }

    public static ExecutionStreamEvent runtimeEvent(long streamSequence, AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return new ExecutionStreamEvent(
                event.runId(),
                streamSequence,
                event.occurredAt(),
                ExecutionStreamEventKind.RUNTIME_EVENT,
                new RuntimeEventPayload(event)
        );
    }

    public static ExecutionStreamEvent modelDelta(
            String runId,
            long streamSequence,
            Instant occurredAt,
            ModelDelta delta
    ) {
        return new ExecutionStreamEvent(
                runId,
                streamSequence,
                occurredAt,
                ExecutionStreamEventKind.MODEL_DELTA,
                delta
        );
    }

    public static ExecutionStreamEvent controlEvent(
            String runId,
            long streamSequence,
            Instant occurredAt,
            ControlEvent controlEvent
    ) {
        return new ExecutionStreamEvent(
                runId,
                streamSequence,
                occurredAt,
                ExecutionStreamEventKind.CONTROL_EVENT,
                controlEvent
        );
    }
}
