package com.multimodalAgent.agent.runtime.support;

import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TraceAssertions {

    private static final Set<AgentEventType> RUN_TERMINALS = Set.of(
            AgentEventType.RUN_COMPLETED,
            AgentEventType.RUN_STOPPED,
            AgentEventType.RUN_WAITING_APPROVAL
    );

    private TraceAssertions() {
    }

    public static void assertSingleRunTerminal(List<AgentEvent> events) {
        assertEquals(1, events.stream()
                .map(AgentEvent::type)
                .filter(RUN_TERMINALS::contains)
                .count(), "exactly one core run terminal event is required");
    }

    public static void assertNoEvent(List<AgentEvent> events, AgentEventType... forbidden) {
        Set<AgentEventType> forbiddenTypes = Set.copyOf(Arrays.asList(forbidden));
        assertFalse(events.stream().map(AgentEvent::type).anyMatch(forbiddenTypes::contains),
                () -> "forbidden event present; forbidden=" + forbiddenTypes
                        + ", actual=" + events.stream().map(AgentEvent::type).toList());
    }

    public static void assertNoEventsAfterRunTerminal(List<AgentEvent> events) {
        int terminalIndex = -1;
        for (int index = 0; index < events.size(); index++) {
            if (RUN_TERMINALS.contains(events.get(index).type())) {
                assertEquals(-1, terminalIndex, "multiple core run terminal events found");
                terminalIndex = index;
            }
        }
        assertTrue(terminalIndex >= 0, "core run terminal event is missing");
        assertEquals(events.size() - 1, terminalIndex,
                "core events must not occur after the run terminal event");
    }

    public static void assertEventOrder(
            List<AgentEvent> events,
            AgentEventType earlier,
            AgentEventType later
    ) {
        int earlierIndex = indexOf(events, earlier);
        int laterIndex = indexOf(events, later);
        assertTrue(earlierIndex >= 0, () -> "missing earlier event " + earlier);
        assertTrue(laterIndex >= 0, () -> "missing later event " + later);
        assertTrue(earlierIndex < laterIndex,
                () -> earlier + " must occur before " + later);
    }

    private static int indexOf(List<AgentEvent> events, AgentEventType type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).type() == type) {
                return index;
            }
        }
        return -1;
    }
}
