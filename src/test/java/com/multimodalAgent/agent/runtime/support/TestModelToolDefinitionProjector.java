package com.multimodalAgent.agent.runtime.support;

import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;

import java.util.Map;

public enum TestModelToolDefinitionProjector implements ModelToolDefinitionProjector {
    INSTANCE;

    @Override
    public ModelToolDefinition project(ToolDescriptor<?> descriptor) {
        return new ModelToolDefinition(
                descriptor.name(),
                descriptor.description(),
                Map.of("type", "object")
        );
    }
}
