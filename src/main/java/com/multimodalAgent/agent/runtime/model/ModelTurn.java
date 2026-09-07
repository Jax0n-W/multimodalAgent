package com.multimodalAgent.agent.runtime.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record ModelTurn(
        ModelFinishReason finishReason,
        String content,
        List<ToolCall> toolCalls,
        TokenUsage tokenUsage
) {

    public ModelTurn {
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        tokenUsage = tokenUsage == null ? TokenUsage.ZERO : tokenUsage;

        if (finishReason == ModelFinishReason.STOP && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("A final model turn must not contain tool calls");
        }
        if (finishReason == ModelFinishReason.TOOL_CALLS && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("A tool-call model turn must contain at least one call");
        }
    }

    public static ModelTurn finalAnswer(String content) {
        return new ModelTurn(ModelFinishReason.STOP, content, List.of(), TokenUsage.ZERO);
    }

    public static ModelTurn toolCall(ToolCall... toolCalls) {
        Objects.requireNonNull(toolCalls, "toolCalls must not be null");
        return new ModelTurn(ModelFinishReason.TOOL_CALLS, "", Arrays.asList(toolCalls), TokenUsage.ZERO);
    }
}
