package com.multimodalAgent.agent.runtime.event;

import java.util.Objects;

public record RunWaitingApprovalEvent(AgentEventMetadata metadata) implements AgentEvent {

    public RunWaitingApprovalEvent {
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    @Override
    public AgentEventType type() {
        return AgentEventType.RUN_WAITING_APPROVAL;
    }
}
