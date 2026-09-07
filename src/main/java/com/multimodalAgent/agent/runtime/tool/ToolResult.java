package com.multimodalAgent.agent.runtime.tool;

public record ToolResult(boolean success, String content) {

    public ToolResult {
        content = content == null ? "" : content;
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult failure(String content) {
        return new ToolResult(false, content);
    }
}
