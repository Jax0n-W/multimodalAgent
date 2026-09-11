package com.multimodalAgent.agent.runtime.event;

public final class NoopAgentEventPublisher implements AgentEventPublisher {

    public static final NoopAgentEventPublisher INSTANCE = new NoopAgentEventPublisher();

    private NoopAgentEventPublisher() {
    }

    @Override
    public void publish(AgentEvent event) {
        // Intentionally empty: observability is optional for runtime correctness.
    }
}
