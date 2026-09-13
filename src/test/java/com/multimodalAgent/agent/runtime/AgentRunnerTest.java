package com.multimodalAgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldCompleteWithDirectModelAnswer() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.finalAnswer("Redis Sentinel 是 Redis 的高可用组件。")
        );
        AgentRunner runner = runner(model, List.of());

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, result.iterations());
        assertEquals("Redis Sentinel 是 Redis 的高可用组件。", result.finalContent());
        assertTrue(result.toolsUsed().isEmpty());
    }

    @Test
    void shouldCompleteToolCallingLoopAndFeedToolResultBackToModel() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.toolCall(new ToolCall(
                        "call-1",
                        "knowledge_search",
                        Map.of("query", "Redis Sentinel")
                )),
                ModelTurn.finalAnswer("Redis Sentinel 用于 Redis 主从架构中的自动故障转移。")
        );
        AgentRunner runner = runner(model, List.of(new FakeKnowledgeSearchTool()));

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(2, result.iterations());
        assertEquals(List.of("knowledge_search"), result.toolsUsed());
        assertNotNull(result.finalContent());

        List<AgentMessage> secondModelRequest = model.requests().get(1);
        assertEquals(3, secondModelRequest.size());
        assertEquals(AgentMessageRole.ASSISTANT, secondModelRequest.get(1).role());
        assertEquals("call-1", secondModelRequest.get(1).toolCalls().get(0).id());
        assertEquals(AgentMessageRole.TOOL, secondModelRequest.get(2).role());
        assertEquals("call-1", secondModelRequest.get(2).toolCallId());
        assertEquals(
                "Redis Sentinel provides automatic failover.",
                secondModelRequest.get(2).content()
        );
    }

    @Test
    void shouldStopWhenModelRequestsUnknownTool() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.toolCall(new ToolCall(
                        "call-unknown",
                        "unknown_tool",
                        Map.of()
                ))
        );
        AgentRunner runner = runner(model, List.of());

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(1, result.iterations());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.toolErrorCode());
        assertEquals("Unknown tool: unknown_tool", result.errorMessage());
    }

    @Test
    void shouldStopAtMaximumIterationsWhenModelKeepsCallingTools() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                knowledgeSearchCall("call-1"),
                knowledgeSearchCall("call-2"),
                knowledgeSearchCall("call-3")
        );
        AgentRunner runner = runner(model, List.of(new FakeKnowledgeSearchTool()));

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(3, result.iterations());
        assertEquals(List.of("knowledge_search"), result.toolsUsed());
    }

    @Test
    void shouldReturnModelErrorWhenModelThrows() {
        AgentModel model = messages -> {
            throw new IllegalStateException("model unavailable");
        };

        AgentRunResult result = runner(model, List.of()).run(spec(3));

        assertEquals(AgentStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(1, result.iterations());
        assertEquals("model unavailable", result.errorMessage());
    }

    @Test
    void shouldReturnToolErrorWhenRegisteredToolThrows() {
        ScriptedAgentModel model = new ScriptedAgentModel(knowledgeSearchCall("call-failing"));

        AgentRunResult result = runner(model, List.of(new ThrowingKnowledgeSearchTool())).run(spec(3));

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.toolErrorCode());
        assertEquals(1, result.iterations());
    }

    @Test
    void shouldExposeOnlyRegisteredToolsAllowedForCurrentRun() {
        ScriptedAgentModel model = new ScriptedAgentModel(ModelTurn.finalAnswer("done"));
        AgentRunner runner = runner(model, List.of(
                new NamedTool("tool_a"),
                new NamedTool("tool_b"),
                new NamedTool("tool_c")
        ));

        AgentRunResult result = runner.run(new AgentRunSpec(
                "run-visible-tools",
                "session-visible-tools",
                List.of(AgentMessage.user("answer")),
                1,
                Set.of("tool_a", "tool_c", "not_registered"),
                Set.of()
        ));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(
                List.of("tool_a", "tool_c"),
                model.modelRequests().get(0).tools().stream()
                        .map(definition -> definition.name())
                        .toList()
        );
    }

    private AgentRunSpec spec(int maxIterations) {
        return new AgentRunSpec(
                "run-001",
                "session-001",
                List.of(AgentMessage.user("Redis Sentinel 是什么？")),
                maxIterations,
                Set.of("knowledge_search"),
                Set.of()
        );
    }

    private ModelTurn knowledgeSearchCall(String callId) {
        return ModelTurn.toolCall(new ToolCall(
                callId,
                "knowledge_search",
                Map.of("query", "Redis Sentinel")
        ));
    }

    private AgentRunner runner(AgentModel model, List<? extends AgentTool<?, ?>> tools) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        return new AgentRunner(model, toolExecutor, TestModelToolDefinitionProjector.INSTANCE);
    }

    private static final class FakeKnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        private static final ToolDescriptor<KnowledgeSearchInput> DESCRIPTOR = new ToolDescriptor<>(
                "knowledge_search",
                "Search the fake knowledge base",
                KnowledgeSearchInput.class,
                ToolRisk.LOW,
                true,
                true,
                false
        );

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            return "Redis Sentinel provides automatic failover.";
        }
    }

    private static final class ThrowingKnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return FakeKnowledgeSearchTool.DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            throw new IllegalStateException("tool unavailable");
        }
    }

    private static final class NamedTool implements AgentTool<KnowledgeSearchInput, String> {

        private final ToolDescriptor<KnowledgeSearchInput> descriptor;

        private NamedTool(String name) {
            this.descriptor = new ToolDescriptor<>(
                    name,
                    "Test visible tool " + name,
                    KnowledgeSearchInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return descriptor;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            return input.query();
        }
    }

}
