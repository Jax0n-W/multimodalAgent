package com.multimodalAgent.agent.runtime.model;

import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;

@FunctionalInterface
public interface ModelToolDefinitionProjector {

    ModelToolDefinition project(ToolDescriptor<?> descriptor);
}
