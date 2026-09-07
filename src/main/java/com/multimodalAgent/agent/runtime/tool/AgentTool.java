package com.multimodalAgent.agent.runtime.tool;

import java.util.Map;

public interface AgentTool {

    String name();

    ToolResult execute(Map<String, Object> arguments);
}
