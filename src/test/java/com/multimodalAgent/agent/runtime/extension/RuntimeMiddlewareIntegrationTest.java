package com.multimodalAgent.agent.runtime.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
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
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.trace.DecisionTrace;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import com.multimodalAgent.agent.runtime.trace.ToolExecutionOutcome;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEvent;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEventsAfterRunTerminal;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertSingleRunTerminal;

class RuntimeMiddlewareIntegrationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final DecisionTraceBuilder TRACE_BUILDER = new DecisionTraceBuilder();

    @Test
    void shouldNotInvokeToolMiddlewareForUnknownTool() {
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "missing_tool", "value")),
                List.of(),
                Set.of("missing_tool"),
                Set.of(),
                middleware
        );

        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, observation.result().toolErrorCode());
        assertEquals(0, middleware.invocations());
    }

    @Test
    void shouldNotInvokeToolMiddlewareForInvalidArguments() {
        CountingTool tool = new CountingTool("test_tool", false, false);
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "")),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                middleware
        );

        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, observation.result().toolErrorCode());
        assertEquals(0, middleware.invocations());
        assertEquals(0, tool.executions());
    }

    @Test
    void shouldNotInvokeToolMiddlewareWhenPolicyDenies() {
        CountingTool tool = new CountingTool("test_tool", false, false);
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "value")),
                List.of(tool),
                Set.of(),
                Set.of(),
                middleware
        );

        assertEquals(AgentStopReason.POLICY_BLOCKED, observation.result().stopReason());
        assertEquals(0, middleware.invocations());
        assertEquals(0, tool.executions());
    }

    @Test
    void shouldNotInvokeToolMiddlewareWhileApprovalIsRequired() {
        CountingTool tool = new CountingTool("approval_tool", true, false);
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "approval_tool", "value")),
                List.of(tool),
                Set.of("approval_tool"),
                Set.of(),
                middleware
        );

        assertEquals(AgentStopReason.WAITING_APPROVAL, observation.result().stopReason());
        assertEquals(0, middleware.invocations());
        assertEquals(0, tool.executions());
    }

    @Test
    void shouldInvokeToolMiddlewareOnceForAllowedTool() {
        CountingTool tool = new CountingTool("test_tool", false, false);
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                successfulModel("call-1", "test_tool"),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                middleware
        );

        assertEquals(AgentStopReason.COMPLETED, observation.result().stopReason());
        assertEquals(1, middleware.invocations());
        assertEquals(1, tool.executions());
    }

    @Test
    void shouldInvokeToolMiddlewareOnceForApprovedToolCall() {
        CountingTool tool = new CountingTool("approval_tool", true, false);
        CountingToolMiddleware middleware = new CountingToolMiddleware();
        Observation observation = execute(
                successfulModel("approved-call", "approval_tool"),
                List.of(tool),
                Set.of("approval_tool"),
                Set.of("approved-call"),
                middleware
        );

        assertEquals(AgentStopReason.COMPLETED, observation.result().stopReason());
        assertEquals(1, middleware.invocations());
        assertEquals(1, tool.executions());
    }

    @Test
    void shouldFailCoordinatorWithoutStartingRunnerWhenRunMiddlewareFailsBeforeProceed() {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        ScriptedAgentModel model = new ScriptedAgentModel(ModelTurn.finalAnswer("answer"));
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                throw new IllegalStateException("run middleware failed");
            }
        };
        AgentExecutionCoordinator coordinator = coordinator(
                model,
                List.of(),
                publisher,
                new RuntimeMiddlewareChain(List.of(failing))
        );

        assertThrows(RuntimeMiddlewareFailureException.class,
                () -> coordinator.execute(request(Set.of(), Set.of())));
        assertEquals(0, model.requests().size());
        assertEquals(List.of(), publisher.events());
    }

    @Test
    void shouldKeepCompletedCoreEventsWhenRunMiddlewareFailsAfterProceed() {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                next.proceed();
                throw new IllegalStateException("run middleware post failed");
            }
        };
        AgentExecutionCoordinator coordinator = coordinator(
                new ScriptedAgentModel(ModelTurn.finalAnswer("answer")),
                List.of(),
                publisher,
                new RuntimeMiddlewareChain(List.of(failing))
        );

        assertThrows(RuntimeMiddlewareFailureException.class,
                () -> coordinator.execute(request(Set.of(), Set.of())));
        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.RUN_COMPLETED
        ), types(publisher.events()));
        assertEquals(1, terminalCount(publisher.events()));
        assertNoEvent(publisher.events(), AgentEventType.RUN_STOPPED,
                AgentEventType.RUN_WAITING_APPROVAL);
        assertSingleRunTerminal(publisher.events());
        assertNoEventsAfterRunTerminal(publisher.events());
    }

    @Test
    void shouldClassifyModelMiddlewarePreFailureAsInternalError() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModel model = messages -> {
            modelCalls.incrementAndGet();
            return ModelTurn.finalAnswer("answer");
        };
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                throw new IllegalStateException("model middleware failed");
            }
        };

        Observation observation = execute(
                model,
                List.of(),
                Set.of(),
                Set.of(),
                failing
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(0, observation.result().iterations());
        assertEquals(0, modelCalls.get());
        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_STOPPED),
                types(observation.events()));
        assertNoEvent(observation.events(), AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED, AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentStopReason.INTERNAL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldClassifyModelMiddlewarePostFailureAsInternalErrorAfterModelCompleted() {
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                next.proceed();
                throw new IllegalStateException("model middleware post failed");
            }
        };

        Observation observation = execute(
                new ScriptedAgentModel(ModelTurn.finalAnswer("answer")),
                List.of(),
                Set.of(),
                Set.of(),
                failing
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(1, observation.result().iterations());
        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.RUN_STOPPED
        ), types(observation.events()));
        assertNoEvent(observation.events(), AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_COMPLETED, AgentEventType.TOOL_STARTED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentStopReason.INTERNAL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldPreserveActualModelFailureClassification() {
        AgentModel model = messages -> {
            throw new IllegalStateException("model unavailable");
        };

        Observation observation = execute(
                model,
                List.of(),
                Set.of(),
                Set.of(),
                new RuntimeMiddleware() {
                }
        );

        assertEquals(AgentStopReason.MODEL_ERROR, observation.result().stopReason());
        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_STOPPED
        ), types(observation.events()));
        assertNoEvent(observation.events(), AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_STARTED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentStopReason.MODEL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldClassifyRuntimeMiddlewareExceptionThrownByModelAsModelError() {
        AgentModel model = messages -> {
            throw new RuntimeMiddlewareException("model exception type collision");
        };

        Observation observation = execute(
                model,
                List.of(),
                Set.of(),
                Set.of(),
                new RuntimeMiddleware() {
                }
        );

        assertEquals(AgentStopReason.MODEL_ERROR, observation.result().stopReason());
        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_STOPPED
        ), types(observation.events()));
        assertNoEvent(observation.events(), AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentStopReason.MODEL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldClassifyToolMiddlewarePreFailureAsInternalErrorWithoutStartingTool() {
        CountingTool tool = new CountingTool("test_tool", false, false);
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                throw new IllegalStateException("tool middleware failed");
            }
        };

        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "value")),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                failing
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(0, tool.executions());
        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.RUN_STOPPED
        ), types(observation.events()));
        assertNoEvent(observation.events(), AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        DecisionTrace trace = TRACE_BUILDER.build(observation.events());
        assertEquals(AgentStopReason.INTERNAL_ERROR, trace.stopReason());
        assertEquals(ToolExecutionOutcome.NOT_STARTED,
                trace.orderedToolDecisions().get(0).executionOutcome());
    }

    @Test
    void shouldPreserveActualToolFailureClassification() {
        CountingTool tool = new CountingTool("test_tool", false, true);

        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "value")),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                new RuntimeMiddleware() {
                }
        );

        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertEquals(1, tool.executions());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, observation.result().toolErrorCode());
        assertNoEvent(observation.events(), AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentEventType.TOOL_FAILED,
                observation.events().get(observation.events().size() - 2).type());
        assertEquals(AgentStopReason.TOOL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldClassifyRuntimeMiddlewareExceptionThrownByToolAsToolError() {
        CountingTool tool = new CountingTool(
                "test_tool",
                false,
                new RuntimeMiddlewareException("tool exception type collision")
        );

        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "value")),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                new RuntimeMiddleware() {
                }
        );

        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertEquals(1, tool.executions());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, observation.result().toolErrorCode());
        assertEquals(AgentEventType.TOOL_FAILED,
                observation.events().get(observation.events().size() - 2).type());
        assertEquals(AgentStopReason.TOOL_ERROR,
                TRACE_BUILDER.build(observation.events()).stopReason());
    }

    @Test
    void shouldKeepToolSucceededFactWhenToolMiddlewareFailsAfterProceed() {
        CountingTool tool = new CountingTool("test_tool", false, false);
        RuntimeMiddleware failing = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                next.proceed();
                throw new IllegalStateException("tool middleware post failed");
            }
        };

        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-1", "test_tool", "value")),
                List.of(tool),
                Set.of("test_tool"),
                Set.of(),
                failing
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(1, tool.executions());
        assertEquals(1, types(observation.events()).stream()
                .filter(type -> type == AgentEventType.TOOL_SUCCEEDED)
                .count());
        assertFalse(types(observation.events()).contains(AgentEventType.TOOL_FAILED));
        assertNoEvent(observation.events(), AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        DecisionTrace trace = TRACE_BUILDER.build(observation.events());
        assertEquals(ToolExecutionOutcome.SUCCEEDED,
                trace.orderedToolDecisions().get(0).executionOutcome());
        assertEquals(AgentStopReason.INTERNAL_ERROR, trace.stopReason());
    }

    private Observation execute(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            Set<String> allowedTools,
            Set<String> approvedToolCallIds,
            RuntimeMiddleware middleware
    ) {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        RuntimeMiddlewareChain chain = new RuntimeMiddlewareChain(List.of(middleware));
        AgentExecutionCoordinator coordinator = coordinator(model, tools, publisher, chain);
        AgentRunResult result = coordinator.execute(request(allowedTools, approvedToolCallIds));
        return new Observation(result, publisher.events());
    }

    private AgentExecutionCoordinator coordinator(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            RecordingAgentEventPublisher publisher,
            RuntimeMiddlewareChain chain
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        return new AgentExecutionCoordinator(
                new AgentRunner(
                        model,
                        executor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        publisher
                ),
                chain
        );
    }

    private AgentExecutionRequest request(
            Set<String> allowedTools,
            Set<String> approvedToolCallIds
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-extension",
                        "session-extension",
                        List.of(AgentMessage.user("run")),
                        3,
                        allowedTools,
                        approvedToolCallIds
                ),
                "request-extension",
                7L
        );
    }

    private static ScriptedAgentModel successfulModel(String callId, String toolName) {
        return new ScriptedAgentModel(
                toolTurn(callId, toolName, "value"),
                ModelTurn.finalAnswer("done")
        );
    }

    private static ModelTurn toolTurn(String callId, String toolName, String query) {
        return ModelTurn.toolCall(new ToolCall(
                callId,
                toolName,
                Map.of("query", query)
        ));
    }

    private static List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private static long terminalCount(List<AgentEvent> events) {
        return types(events).stream()
                .filter(type -> type == AgentEventType.RUN_COMPLETED
                        || type == AgentEventType.RUN_STOPPED
                        || type == AgentEventType.RUN_WAITING_APPROVAL)
                .count();
    }

    private record Observation(AgentRunResult result, List<AgentEvent> events) {
    }

    private static final class CountingToolMiddleware implements RuntimeMiddleware {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ToolResult aroundToolExecution(
                AgentRuntimeContext context,
                ToolExecutionMetadata metadata,
                RuntimeInvocation<ToolResult> next
        ) {
            invocations.incrementAndGet();
            return next.proceed();
        }

        private int invocations() {
            return invocations.get();
        }
    }

    private record TestInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<TestInput, String> {

        private final ToolDescriptor<TestInput> descriptor;
        private final RuntimeException failure;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name, boolean requiresApproval, boolean fail) {
            this(
                    name,
                    requiresApproval,
                    fail ? new IllegalStateException("tool failed") : null
            );
        }

        private CountingTool(
                String name,
                boolean requiresApproval,
                RuntimeException failure
        ) {
            descriptor = new ToolDescriptor<>(
                    name,
                    "Test tool",
                    TestInput.class,
                    requiresApproval ? ToolRisk.MEDIUM : ToolRisk.LOW,
                    !requiresApproval,
                    true,
                    requiresApproval
            );
            this.failure = failure;
        }

        @Override
        public ToolDescriptor<TestInput> descriptor() {
            return descriptor;
        }

        @Override
        public String execute(TestInput input) {
            executions.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return input.query();
        }

        private int executions() {
            return executions.get();
        }
    }
}
