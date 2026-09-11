package com.multimodalAgent.agent.runtime.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RecordingAgentEventPublisher implements AgentEventPublisher {

    private final CopyOnWriteArrayList<AgentEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AgentEvent event) {
        events.add(Objects.requireNonNull(event, "event must not be null"));
    }

    public List<AgentEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
