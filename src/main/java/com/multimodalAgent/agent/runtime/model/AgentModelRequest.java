package com.multimodalAgent.agent.runtime.model;

import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral input for one model invocation.
 */
public record AgentModelRequest(
        List<AgentMessage> messages,
        List<ModelToolDefinition> tools
) {

    public AgentModelRequest {
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
