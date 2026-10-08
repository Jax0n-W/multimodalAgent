package com.multimodalAgent.agent.context;

import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.List;
import java.util.Objects;

/** Immutable semantic messages returned by one read-only context source. */
public record ContextContribution(List<AgentMessage> messages) {

    public ContextContribution {
        messages = List.copyOf(Objects.requireNonNull(messages, "messages must not be null"));
        messages.forEach(message -> Objects.requireNonNull(
                message,
                "messages must not contain null"
        ));
    }
}
