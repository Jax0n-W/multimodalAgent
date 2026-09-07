package com.multimodalAgent.agent.runtime.tool;

import java.util.Objects;

public record ToolError(ToolErrorCode code, String message) {

    public ToolError {
        Objects.requireNonNull(code, "code must not be null");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Tool error message must not be blank");
        }
    }
}
