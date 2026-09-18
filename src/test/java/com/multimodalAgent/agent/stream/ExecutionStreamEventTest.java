package com.multimodalAgent.agent.stream;

import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.RunStartedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionStreamEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-17T08:00:00Z");

    @Test
    void requiresRunId() {
        ModelDelta delta = new ModelDelta(1, "hello");

        assertThrows(IllegalArgumentException.class, () -> ExecutionStreamEvent.modelDelta(
                " ", 1, OCCURRED_AT, delta
        ));
    }

    @Test
    void streamSequenceStartsAtOne() {
        ModelDelta delta = new ModelDelta(1, "hello");

        assertEquals(1L, ExecutionStreamEvent.INITIAL_SEQUENCE);
        assertThrows(IllegalArgumentException.class, () -> ExecutionStreamEvent.modelDelta(
                "run-1", 0, OCCURRED_AT, delta
        ));
        assertEquals(1L, ExecutionStreamEvent.modelDelta(
                "run-1", 1, OCCURRED_AT, delta
        ).streamSequence());
    }

    @Test
    void requiresTimestampKindAndPayload() {
        ModelDelta delta = new ModelDelta(1, "hello");

        assertThrows(NullPointerException.class, () -> new ExecutionStreamEvent(
                "run-1", 1, null, ExecutionStreamEventKind.MODEL_DELTA, delta
        ));
        assertThrows(NullPointerException.class, () -> new ExecutionStreamEvent(
                "run-1", 1, OCCURRED_AT, null, delta
        ));
        assertThrows(NullPointerException.class, () -> new ExecutionStreamEvent(
                "run-1", 1, OCCURRED_AT, ExecutionStreamEventKind.MODEL_DELTA, null
        ));
    }

    @Test
    void rejectsMismatchedKindAndPayload() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionStreamEvent(
                "run-1",
                1,
                OCCURRED_AT,
                ExecutionStreamEventKind.CONTROL_EVENT,
                new ModelDelta(1, "hello")
        ));
    }

    @Test
    void modelDeltaRequiresPositiveIteration() {
        assertThrows(IllegalArgumentException.class, () -> new ModelDelta(0, "hello"));
    }

    @Test
    void modelDeltaRequiresNonEmptyContentButPreservesWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> new ModelDelta(1, null));
        assertThrows(IllegalArgumentException.class, () -> new ModelDelta(1, ""));
        assertEquals(" ", new ModelDelta(1, " ").content());
    }

    @Test
    void runtimeWrapperPreservesOriginalEventIdentityAndData() {
        AgentEventMetadata metadata = new AgentEventMetadata(
                "event-1", "run-1", 7, OCCURRED_AT, 0
        );
        RunStartedEvent runtimeEvent = new RunStartedEvent(metadata);

        ExecutionStreamEvent streamEvent = ExecutionStreamEvent.runtimeEvent(3, runtimeEvent);
        RuntimeEventPayload payload = (RuntimeEventPayload) streamEvent.payload();

        assertEquals("run-1", streamEvent.runId());
        assertEquals(3, streamEvent.streamSequence());
        assertEquals(ExecutionStreamEventKind.RUNTIME_EVENT, streamEvent.kind());
        assertSame(runtimeEvent, payload.event());
        assertSame(metadata, payload.event().metadata());
        assertEquals(7, payload.event().sequence());
    }

    @Test
    void runtimeWrapperRejectsDifferentEnvelopeRunId() {
        RunStartedEvent runtimeEvent = new RunStartedEvent(new AgentEventMetadata(
                "event-1", "run-1", 1, OCCURRED_AT, 0
        ));

        assertThrows(IllegalArgumentException.class, () -> new ExecutionStreamEvent(
                "run-2",
                1,
                OCCURRED_AT,
                ExecutionStreamEventKind.RUNTIME_EVENT,
                new RuntimeEventPayload(runtimeEvent)
        ));
    }

    @Test
    void factoriesKeepPayloadsTyped() {
        ModelDelta delta = new ModelDelta(2, "world");
        ControlEvent control = new ControlEvent(ExecutionControlState.CANCEL_REQUESTED);

        ExecutionStreamEvent deltaEvent = ExecutionStreamEvent.modelDelta(
                "run-1", 4, OCCURRED_AT, delta
        );
        ExecutionStreamEvent controlEvent = ExecutionStreamEvent.controlEvent(
                "run-1", 5, OCCURRED_AT, control
        );

        assertSame(delta, deltaEvent.payload());
        assertEquals(ExecutionStreamEventKind.MODEL_DELTA, deltaEvent.kind());
        assertSame(control, controlEvent.payload());
        assertEquals(ExecutionStreamEventKind.CONTROL_EVENT, controlEvent.kind());
    }
}
