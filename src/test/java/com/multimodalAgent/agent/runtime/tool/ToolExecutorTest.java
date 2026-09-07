package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldDeserializeValidateAndExecuteWithValidArguments() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ToolExecutor executor = executor(tool);

        ToolResult result = executor.execute(call(Map.of(
                "query", "Redis Sentinel",
                "topK", 5
        )));

        assertTrue(result.success());
        assertEquals("result for Redis Sentinel, topK=5", result.content());
        assertEquals(1, tool.executionCount());
        assertEquals(new KnowledgeSearchInput("Redis Sentinel", 5), tool.lastInput());
    }

    @Test
    void shouldRejectMissingRequiredFieldBeforeToolExecution() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ToolExecutor executor = executor(tool);

        ToolResult result = executor.execute(call(Map.of("topK", 5)));

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.error().code());
        assertEquals(0, tool.executionCount());
    }

    @Test
    void shouldRejectWrongArgumentTypeBeforeToolExecution() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ToolExecutor executor = executor(tool);

        ToolResult result = executor.execute(call(Map.of(
                "query", "Redis Sentinel",
                "topK", "abc"
        )));

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.error().code());
        assertEquals(0, tool.executionCount());
    }

    @Test
    void shouldRejectConstraintViolationBeforeToolExecution() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ToolExecutor executor = executor(tool);

        ToolResult result = executor.execute(call(Map.of(
                "query", "Redis Sentinel",
                "topK", -1
        )));

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.error().code());
        assertEquals(0, tool.executionCount());
    }

    @Test
    void shouldReturnStructuredErrorForUnknownTool() {
        ToolExecutor executor = executor();
        ToolCall call = new ToolCall("call-unknown", "unknown_tool", Map.of());

        ToolResult result = executor.execute(call);

        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.error().code());
        assertEquals("Unknown tool: unknown_tool", result.error().message());
    }

    private ToolCall call(Map<String, Object> arguments) {
        return new ToolCall("call-1", "knowledge_search", arguments);
    }

    private ToolExecutor executor(AgentTool<?, ?>... tools) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ToolExecutor(
                new ToolRegistry(List.of(tools)),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                objectMapper
        );
    }

    private static final class CountingKnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        private static final ToolDescriptor<KnowledgeSearchInput> DESCRIPTOR = new ToolDescriptor<>(
                "knowledge_search",
                "Search the test knowledge base",
                KnowledgeSearchInput.class
        );

        private int executionCount;
        private KnowledgeSearchInput lastInput;

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            executionCount++;
            lastInput = input;
            return "result for " + input.query() + ", topK=" + input.effectiveTopK();
        }

        int executionCount() {
            return executionCount;
        }

        KnowledgeSearchInput lastInput() {
            return lastInput;
        }
    }
}
