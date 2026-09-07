package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.util.Map;
import java.util.Objects;

public final class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver argumentResolver;
    private final ObjectMapper objectMapper;

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.argumentResolver = Objects.requireNonNull(argumentResolver, "argumentResolver must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public ToolResult execute(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        AgentTool<?, ?> tool = toolRegistry.find(toolCall.name()).orElse(null);
        if (tool == null) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_FOUND,
                    "Unknown tool: " + toolCall.name()
            );
        }

        try {
            return executeTyped(tool, toolCall.arguments());
        } catch (ToolValidationException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    "Tool execution failed: " + tool.name()
            );
        }
    }

    private <I, O> ToolResult executeTyped(AgentTool<I, O> tool, Map<String, Object> arguments) {
        I input = argumentResolver.resolve(arguments, tool.descriptor().inputType());
        O output = tool.execute(input);
        return ToolResult.success(serialize(output));
    }

    private String serialize(Object output) {
        if (output == null) {
            return "null";
        }
        if (output instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool output could not be serialized", exception);
        }
    }
}
