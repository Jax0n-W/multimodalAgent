package com.multimodalAgent.agent.runtime.tool.policy;

import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;

import java.util.Objects;

public record ToolPolicyRequest(
        ToolCall toolCall,
        ToolDescriptor<?> descriptor,
        Object validatedInput,
        ToolPolicyContext context
) {

    public ToolPolicyRequest {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(validatedInput, "validatedInput must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }
}
