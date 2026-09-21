package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartialToolCallAssemblerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assemblesIncrementalIdentityNameAndArgumentsExactlyOnce() {
        PartialToolCallAssembler assembler = new PartialToolCallAssembler();

        assembler.accept(List.of(fragment(
                0,
                "call-",
                "function",
                "get",
                "{\"city\":"
        )));
        assembler.accept(List.of(fragment(
                0,
                "123",
                null,
                "Weather",
                "\"Singapore\"}"
        )));

        ToolCall call = assembler.assemble(objectMapper, "test-provider").get(0);

        assertEquals("call-123", call.id());
        assertEquals("getWeather", call.name());
        assertEquals("Singapore", call.arguments().get("city"));
    }

    @Test
    void correlatesInterleavedCallsByProviderIndexAndReturnsProviderOrder() {
        PartialToolCallAssembler assembler = new PartialToolCallAssembler();

        assembler.accept(List.of(fragment(0, "call-a", "function", "alpha", "{\"v\":\"")));
        assembler.accept(List.of(fragment(1, "call-b", "function", "beta", "{\"v\":\"")));
        assembler.accept(List.of(fragment(0, null, null, null, "A\"}")));
        assembler.accept(List.of(fragment(2, "call-c", "function", "gamma", "{\"v\":\"C\"}")));
        assembler.accept(List.of(fragment(1, null, null, null, "B\"}")));

        List<ToolCall> calls = assembler.assemble(objectMapper, "test-provider");

        assertEquals(List.of("call-a", "call-b", "call-c"),
                calls.stream().map(ToolCall::id).toList());
        assertEquals(List.of("alpha", "beta", "gamma"),
                calls.stream().map(ToolCall::name).toList());
        assertEquals(List.of("A", "B", "C"),
                calls.stream().map(call -> call.arguments().get("v")).toList());
    }

    @Test
    void missingProviderToolCallIdFailsClosed() {
        PartialToolCallAssembler assembler = new PartialToolCallAssembler();
        assembler.accept(List.of(fragment(0, null, "function", "lookup", "{}")));

        assertThrows(
                SpringAiModelAdapterException.class,
                () -> assembler.assemble(objectMapper, "test-provider")
        );
    }

    @Test
    void missingProviderToolCallTypeFailsClosed() {
        PartialToolCallAssembler assembler = new PartialToolCallAssembler();
        assembler.accept(List.of(fragment(0, "call-1", null, "lookup", "{}")));

        assertThrows(
                SpringAiModelAdapterException.class,
                () -> assembler.assemble(objectMapper, "test-provider")
        );
    }

    private OpenAiApi.ChatCompletionMessage.ToolCall fragment(
            int index,
            String id,
            String type,
            String name,
            String arguments
    ) {
        return new OpenAiApi.ChatCompletionMessage.ToolCall(
                index,
                id,
                type,
                new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(name, arguments)
        );
    }
}
