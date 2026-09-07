package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerTest {

    @Test
    void shouldCompleteWithDirectModelAnswer() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.finalAnswer("Redis Sentinel 是 Redis 的高可用组件。")
        );
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()));

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
        AgentRunner runner = new AgentRunner(
                model,
                new ToolRegistry(List.of(new FakeKnowledgeSearchTool()))
        );

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
        AgentRunner runner = new AgentRunner(model, new ToolRegistry(List.of()));

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(1, result.iterations());
        assertEquals("Unknown tool: unknown_tool", result.errorMessage());
    }

    @Test
    void shouldStopAtMaximumIterationsWhenModelKeepsCallingTools() {
        ScriptedAgentModel model = new ScriptedAgentModel(
                knowledgeSearchCall("call-1"),
                knowledgeSearchCall("call-2"),
                knowledgeSearchCall("call-3")
        );
        AgentRunner runner = new AgentRunner(
                model,
                new ToolRegistry(List.of(new FakeKnowledgeSearchTool()))
        );

        AgentRunResult result = runner.run(spec(3));

        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(3, result.iterations());
        assertEquals(List.of("knowledge_search"), result.toolsUsed());
    }

    private AgentRunSpec spec(int maxIterations) {
        return new AgentRunSpec(
                "run-001",
                "session-001",
                List.of(AgentMessage.user("Redis Sentinel 是什么？")),
                maxIterations
        );
    }

    private ModelTurn knowledgeSearchCall(String callId) {
        return ModelTurn.toolCall(new ToolCall(
                callId,
                "knowledge_search",
                Map.of("query", "Redis Sentinel")
        ));
    }

    private static final class FakeKnowledgeSearchTool implements AgentTool {

        @Override
        public String name() {
            return "knowledge_search";
        }

        @Override
        public ToolResult execute(Map<String, Object> arguments) {
            return ToolResult.success("Redis Sentinel provides automatic failover.");
        }
    }
}
