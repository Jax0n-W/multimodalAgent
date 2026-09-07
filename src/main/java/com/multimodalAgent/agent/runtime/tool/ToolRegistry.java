package com.multimodalAgent.agent.runtime.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(Collection<? extends AgentTool> tools) {
        Objects.requireNonNull(tools, "tools must not be null");
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            Objects.requireNonNull(tool, "tool must not be null");
            if (tool.name() == null || tool.name().isBlank()) {
                throw new IllegalArgumentException("Tool name must not be blank");
            }
            if (registered.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}
