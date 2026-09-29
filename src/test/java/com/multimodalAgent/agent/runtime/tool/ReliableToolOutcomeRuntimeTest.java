package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.budget.BudgetSession;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableToolOutcomeRuntimeTest {

    @Test
    void exactOutcomeIsRecordedBeforeToolSucceeded() {
        List<String> order = new ArrayList<>();
        ToolExecutor executor = executor((runId, callId, toolName, payload) -> {
            assertEquals("run-reliable", runId);
            assertEquals("call-reliable", callId);
            assertEquals("knowledge_search", toolName);
            assertEquals("exact result", payload);
            order.add("OUTCOME");
        });

        ToolResult result = execute(executor, eventType -> order.add(eventType.name()));

        assertTrue(result.success());
        assertEquals("exact result", result.messageForModel());
        assertTrue(order.indexOf("TOOL_STARTED") < order.indexOf("OUTCOME"));
        assertTrue(order.indexOf("OUTCOME") < order.indexOf("TOOL_SUCCEEDED"));
    }

    @Test
    void outcomePersistenceFailureLeavesAttemptStartedWithoutTerminalEvent() {
        List<AgentEventType> events = new ArrayList<>();
        ToolExecutor executor = executor((runId, callId, toolName, payload) -> {
            throw new IllegalStateException("database unavailable");
        });

        assertThrows(
                ToolOutcomeRecordingException.class,
                () -> execute(executor, events::add)
        );

        assertTrue(events.contains(AgentEventType.TOOL_STARTED));
        assertFalse(events.contains(AgentEventType.TOOL_SUCCEEDED));
        assertFalse(events.contains(AgentEventType.TOOL_FAILED));
    }

    private ToolResult execute(
            ToolExecutor executor,
            java.util.function.Consumer<AgentEventType> events
    ) {
        ToolPolicyContext policy = new ToolPolicyContext(
                "run-reliable",
                "session-reliable",
                Set.of("knowledge_search"),
                Set.of()
        );
        return executor.execute(
                new ToolCall(
                        "call-reliable",
                        "knowledge_search",
                        Map.of("query", "recovery", "topK", 1)
                ),
                policy,
                new AgentEventEmitter("run-reliable", event -> events.accept(event.type())),
                1,
                AgentRuntimeContext.minimal("run-reliable", "session-reliable"),
                RuntimeMiddlewareChain.empty(),
                new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
        );
    }

    private ToolExecutor executor(ToolOutcomeRecorder recorder) {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTool<KnowledgeSearchInput, String> tool = new AgentTool<>() {
            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return new ToolDescriptor<>(
                        "knowledge_search",
                        "search",
                        KnowledgeSearchInput.class,
                        ToolRisk.LOW,
                        true,
                        true,
                        false
                );
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                return "exact result";
            }
        };
        return new ToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper,
                recorder
        );
    }
}
