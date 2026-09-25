package com.multimodalAgent.agent.runtime.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
        tokenUsage = tokenUsage == null ? TokenUsage.UNKNOWN : tokenUsage;

        if ((finishReason == ModelFinishReason.STOP
                || finishReason == ModelFinishReason.LENGTH)
                && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "A terminal text model turn must not contain tool calls"
            );
        }
        if (finishReason == ModelFinishReason.TOOL_CALLS && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("A tool-call model turn must contain at least one call");
        }
        Set<String> toolCallIds = new HashSet<>();
        for (ToolCall toolCall : toolCalls) {
            if (!toolCallIds.add(toolCall.id())) {
                throw new IllegalArgumentException("Duplicate tool call id: " + toolCall.id());
            }
        }
    }

    public static ModelTurn finalAnswer(String content) {
        return new ModelTurn(ModelFinishReason.STOP, content, List.of(), TokenUsage.ZERO);
    }

    public static ModelTurn outputLimit(String partialContent) {
        return new ModelTurn(
                ModelFinishReason.LENGTH,
                partialContent,
                List.of(),
                TokenUsage.ZERO
        );
    }

    public static ModelTurn toolCall(ToolCall... toolCalls) {
        Objects.requireNonNull(toolCalls, "toolCalls must not be null");
        return new ModelTurn(ModelFinishReason.TOOL_CALLS, "", Arrays.asList(toolCalls), TokenUsage.ZERO);
    }
}
