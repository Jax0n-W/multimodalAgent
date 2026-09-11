package com.multimodalAgent.agent.runtime.event;

@FunctionalInterface
public interface AgentEventPublisher {

    void publish(AgentEvent event);
}
