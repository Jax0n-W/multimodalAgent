package com.multimodalAgent.agent.runtime.tool;

import java.util.Objects;

public record ToolResult(String content, ToolError error) {

    public ToolResult {
        content = content == null ? "" : content;
    }

    public static ToolResult success(String content) {
        return new ToolResult(content, null);
    }

    public static ToolResult failure(ToolErrorCode code, String message) {
        return new ToolResult("", new ToolError(code, message));
    }

    public boolean success() {
        return error == null;
    }

    public String messageForModel() {
        return success() ? content : Objects.requireNonNull(error).message();
    }
}
