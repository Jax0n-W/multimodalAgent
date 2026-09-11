package com.multimodalAgent.agent.runtime.event;

import java.time.Instant;

public sealed interface AgentEvent permits
        RunStartedEvent,
        ModelStartedEvent,
        ModelCompletedEvent,
        ModelFailedEvent,
        ToolRequestedEvent,
        ToolValidatedEvent,
        ToolValidationFailedEvent,
        ToolPolicyEvaluatedEvent,
        ToolStartedEvent,
        ToolSucceededEvent,
        ToolFailedEvent,
        RunWaitingApprovalEvent,
        RunCompletedEvent,
        RunStoppedEvent {

    AgentEventMetadata metadata();

    AgentEventType type();

    default String eventId() {
        return metadata().eventId();
    }

    default String runId() {
        return metadata().runId();
    }

    default long sequence() {
        return metadata().sequence();
    }

    default Instant occurredAt() {
        return metadata().occurredAt();
    }

    default int iteration() {
        return metadata().iteration();
    }
}
