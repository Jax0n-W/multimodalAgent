package com.multimodalAgent.agent.runtime.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTurnTest {

    @Test
    void shouldRejectNullFinishReason() {
        assertThrows(NullPointerException.class,
                () -> new ModelTurn(null, "", List.of(), TokenUsage.ZERO));
    }

    @Test
    void shouldRejectStopTurnWithToolCalls() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelTurn(
                        ModelFinishReason.STOP,
                        "answer",
                        List.of(call("call-1")),
                        TokenUsage.ZERO
                ));
    }

    @Test
    void shouldRejectToolCallTurnWithoutToolCalls() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModelTurn(
                        ModelFinishReason.TOOL_CALLS,
                        "",
                        List.of(),
                        TokenUsage.ZERO
                ));
    }

    @Test
    void shouldNormalizeNullToolCallsForStopTurn() {
        ModelTurn turn = new ModelTurn(
                ModelFinishReason.STOP,
                "answer",
                null,
                TokenUsage.ZERO
        );

        assertTrue(turn.toolCalls().isEmpty());
    }

    @Test
    void shouldRejectNullToolCallElement() {
        assertThrows(NullPointerException.class,
                () -> new ModelTurn(
                        ModelFinishReason.TOOL_CALLS,
                        "",
                        java.util.Arrays.asList(call("call-1"), null),
                        TokenUsage.ZERO
                ));
    }

    @Test
    void shouldRejectBlankToolName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolCall("call-1", " ", Map.of()));
    }

    @Test
    void shouldRejectDuplicateToolCallIdsWithinTurn() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModelTurn.toolCall(call("duplicate"), call("duplicate"))
        );

        assertEquals("Duplicate tool call id: duplicate", exception.getMessage());
    }

    private static ToolCall call(String id) {
        return new ToolCall(id, "test_tool", Map.of("query", "value"));
    }
}
