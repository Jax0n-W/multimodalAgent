package com.multimodalAgent.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentExecutionCoordinatorTest {

    @Test
    void shouldPropagateOneContextInstanceAcrossRunModelAndToolMiddleware() {
        AtomicReference<AgentRuntimeContext> runContext = new AtomicReference<>();
        AtomicReference<AgentRuntimeContext> modelContext = new AtomicReference<>();
        AtomicReference<AgentRuntimeContext> toolContext = new AtomicReference<>();
        AtomicReference<ModelCallMetadata> modelMetadata = new AtomicReference<>();
        AtomicReference<ToolExecutionMetadata> toolMetadata = new AtomicReference<>();
        RuntimeMiddleware capturing = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                runContext.set(context);
                return next.proceed();
            }

            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                modelContext.compareAndSet(null, context);
                modelMetadata.compareAndSet(null, metadata);
                return next.proceed();
            }

            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                toolContext.set(context);
                toolMetadata.set(metadata);
                return next.proceed();
            }
        };
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.toolCall(new ToolCall(
                        "call-context",
                        "context_tool",
                        Map.of("query", "value")
                )),
                ModelTurn.finalAnswer("done")
        );
        ContextTool tool = new ContextTool();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentExecutionCoordinator coordinator = new AgentExecutionCoordinator(
                new AgentRunner(model, executor, TestModelToolDefinitionProjector.INSTANCE),
                new RuntimeMiddlewareChain(List.of(capturing))
        );
        AgentExecutionRequest request = new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-context",
                        "session-context",
                        List.of(AgentMessage.user("run")),
                        3,
                        Set.of("context_tool"),
                        Set.of()
                ),
                "request-context",
                42L,
                "config-snapshot-1",
                () -> false
        );

        AgentRunResult result = coordinator.execute(request);

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertSame(runContext.get(), modelContext.get());
        assertSame(runContext.get(), toolContext.get());
        assertEquals("run-context", runContext.get().runId());
        assertEquals("request-context", runContext.get().requestId());
        assertEquals("session-context", runContext.get().sessionId());
        assertEquals(42L, runContext.get().userId());
        assertEquals("config-snapshot-1", runContext.get().runtimeConfigSnapshotId());
        assertEquals(new ModelCallMetadata(1, 1), modelMetadata.get());
        assertEquals(new ToolExecutionMetadata("call-context", "context_tool", 1),
                toolMetadata.get());
    }

    private record ContextInput(@NotBlank String query) {
    }

    private static final class ContextTool implements AgentTool<ContextInput, String> {

        private static final ToolDescriptor<ContextInput> DESCRIPTOR = new ToolDescriptor<>(
                "context_tool",
                "Context propagation test tool",
                ContextInput.class,
                ToolRisk.LOW,
                true,
                true,
                false
        );

        @Override
        public ToolDescriptor<ContextInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(ContextInput input) {
            return input.query();
        }
    }
}
