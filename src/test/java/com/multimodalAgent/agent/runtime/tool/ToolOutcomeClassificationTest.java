package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.budget.BudgetBlockedException;
import com.multimodalAgent.agent.runtime.budget.BudgetSession;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.control.ExecutionCancelledException;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolOutcomeClassificationTest {

    @Test
    void successfulExecutionWithSerializationFailureHasNoTerminalToolEvent() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        AtomicInteger recorderCalls = new AtomicInteger();
        CountingTool<UnserializableOutput> tool = new CountingTool<>(
                "serialization_tool",
                input -> new UnserializableOutput("completed")
        );
        ToolExecutor executor = executor(
                failingOutputMapper(),
                (runId, callId, toolName, payload) -> recorderCalls.incrementAndGet(),
                tool
        );

        ToolOutcomeSerializationException exception = assertThrows(
                ToolOutcomeSerializationException.class,
                () -> execute(executor, tool.name(), events)
        );

        assertInstanceOf(JsonProcessingException.class, exception.getCause());
        assertEquals(1, tool.executions());
        assertEquals(0, recorderCalls.get());
        assertEquals(1, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(0, count(events, AgentEventType.TOOL_SUCCEEDED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
    }

    @Test
    void actualToolExecutionFailureRetainsExecutionFailedSemantics() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        AtomicInteger recorderCalls = new AtomicInteger();
        IllegalStateException executionFailure = new IllegalStateException("tool failed");
        CountingTool<String> tool = new CountingTool<>("failing_tool", input -> {
            throw executionFailure;
        });
        ToolExecutor executor = executor(
                new ObjectMapper(),
                (runId, callId, toolName, payload) -> recorderCalls.incrementAndGet(),
                tool
        );

        ToolResult result = execute(executor, tool.name(), events);

        assertFalse(result.success());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.error().code());
        assertEquals(1, tool.executions());
        assertEquals(0, recorderCalls.get());
        assertEquals(1, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(1, count(events, AgentEventType.TOOL_FAILED));
        assertEquals(0, count(events, AgentEventType.TOOL_SUCCEEDED));
    }

    @Test
    void outcomeRecordingFailureRemainsDistinctAfterSuccessfulSerialization() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        AtomicInteger recorderCalls = new AtomicInteger();
        IllegalStateException recordingFailure = new IllegalStateException("database unavailable");
        CountingTool<SerializableOutput> tool = new CountingTool<>(
                "recording_tool",
                input -> new SerializableOutput("serialized")
        );
        ToolExecutor executor = executor(
                new ObjectMapper(),
                (runId, callId, toolName, payload) -> {
                    recorderCalls.incrementAndGet();
                    assertEquals("{\"value\":\"serialized\"}", payload);
                    throw recordingFailure;
                },
                tool
        );

        ToolOutcomeRecordingException exception = assertThrows(
                ToolOutcomeRecordingException.class,
                () -> execute(executor, tool.name(), events)
        );

        assertSame(recordingFailure, exception.getCause());
        assertEquals(1, tool.executions());
        assertEquals(1, recorderCalls.get());
        assertEquals(1, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(0, count(events, AgentEventType.TOOL_SUCCEEDED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
    }

    @Test
    void serializationFailureStopsLaterToolsAndModelTurns() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        AtomicInteger modelCalls = new AtomicInteger();
        CountingTool<UnserializableOutput> first = new CountingTool<>(
                "first_tool",
                input -> new UnserializableOutput("completed")
        );
        CountingTool<String> second = new CountingTool<>(
                "second_tool",
                input -> "must not execute"
        );
        ToolExecutor executor = executor(
                failingOutputMapper(),
                ToolOutcomeRecorder.NOOP,
                first,
                second
        );
        AgentModel model = request -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                return ModelTurn.toolCall(
                        call("call-first", first.name()),
                        call("call-second", second.name())
                );
            }
            return ModelTurn.finalAnswer("must not run");
        };
        AgentRunner runner = new AgentRunner(
                model,
                executor,
                TestModelToolDefinitionProjector.INSTANCE,
                events
        );

        assertThrows(ToolOutcomeSerializationException.class, () -> runner.run(
                new AgentRunSpec(
                        "classification-runner",
                        "classification-session",
                        List.of(AgentMessage.user("run tools")),
                        3,
                        Set.of(first.name(), second.name()),
                        Set.of()
                )
        ));

        assertEquals(1, modelCalls.get());
        assertEquals(1, first.executions());
        assertEquals(0, second.executions());
        assertEquals(1, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(0, count(events, AgentEventType.TOOL_SUCCEEDED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
        assertEquals(0, count(events, AgentEventType.RUN_STOPPED));
    }

    @Test
    void middlewareFailureBeforeInvocationDoesNotClaimToolStartOrFailure() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        CountingTool<String> tool = new CountingTool<>("middleware_tool", input -> "unused");
        ToolExecutor executor = executor(new ObjectMapper(), ToolOutcomeRecorder.NOOP, tool);
        RuntimeMiddleware failingMiddleware = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata metadata,
                    com.multimodalAgent.agent.runtime.extension.RuntimeInvocation<ToolResult> next
            ) {
                throw new IllegalStateException("middleware failed");
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () -> execute(
                executor,
                tool.name(),
                events,
                AgentRuntimeContext.minimal("classification-run", "classification-session"),
                new RuntimeMiddlewareChain(List.of(failingMiddleware)),
                ExecutionBudget.unlimited()
        ));

        assertEquals(0, tool.executions());
        assertNoToolExecutionFacts(events);
    }

    @Test
    void cancellationBeforeInvocationDoesNotClaimToolStartOrFailure() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        CountingTool<String> tool = new CountingTool<>("cancelled_tool", input -> "unused");
        ToolExecutor executor = executor(new ObjectMapper(), ToolOutcomeRecorder.NOOP, tool);
        AgentRuntimeContext cancelled = new AgentRuntimeContext(
                "classification-run",
                null,
                "classification-session",
                null,
                null,
                () -> true,
                new RuntimeAttributes()
        );

        assertThrows(ExecutionCancelledException.class, () -> execute(
                executor,
                tool.name(),
                events,
                cancelled,
                RuntimeMiddlewareChain.empty(),
                ExecutionBudget.unlimited()
        ));

        assertEquals(0, tool.executions());
        assertNoToolExecutionFacts(events);
    }

    @Test
    void budgetBlockBeforeInvocationDoesNotClaimToolStartOrFailure() {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        CountingTool<String> tool = new CountingTool<>("budget_tool", input -> "unused");
        ToolExecutor executor = executor(new ObjectMapper(), ToolOutcomeRecorder.NOOP, tool);

        assertThrows(BudgetBlockedException.class, () -> execute(
                executor,
                tool.name(),
                events,
                AgentRuntimeContext.minimal("classification-run", "classification-session"),
                RuntimeMiddlewareChain.empty(),
                ExecutionBudget.builder().maxToolCalls(0).build()
        ));

        assertEquals(0, tool.executions());
        assertNoToolExecutionFacts(events);
    }

    private ToolResult execute(
            ToolExecutor executor,
            String toolName,
            RecordingAgentEventPublisher events
    ) {
        return execute(
                executor,
                toolName,
                events,
                AgentRuntimeContext.minimal("classification-run", "classification-session"),
                RuntimeMiddlewareChain.empty(),
                ExecutionBudget.unlimited()
        );
    }

    private ToolResult execute(
            ToolExecutor executor,
            String toolName,
            RecordingAgentEventPublisher events,
            AgentRuntimeContext context,
            RuntimeMiddlewareChain middleware,
            ExecutionBudget budget
    ) {
        ToolPolicyContext policy = new ToolPolicyContext(
                "classification-run",
                "classification-session",
                Set.of(toolName),
                Set.of()
        );
        return executor.execute(
                call("classification-call", toolName),
                policy,
                new AgentEventEmitter("classification-run", events),
                1,
                context,
                middleware,
                new BudgetSession(budget, Optional.empty())
        );
    }

    private ToolExecutor executor(
            ObjectMapper mapper,
            ToolOutcomeRecorder recorder,
            AgentTool<?, ?>... tools
    ) {
        return new ToolExecutor(
                new ToolRegistry(List.of(tools)),
                new ToolArgumentResolver(
                        mapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                mapper,
                recorder
        );
    }

    private ObjectMapper failingOutputMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(UnserializableOutput.class, new JsonSerializer<>() {
            @Override
            public void serialize(
                    UnserializableOutput value,
                    JsonGenerator generator,
                    SerializerProvider serializers
            ) throws IOException {
                throw new IOException("forced output serialization failure");
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    private ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, Map.of("query", "classification"));
    }

    private long count(RecordingAgentEventPublisher events, AgentEventType type) {
        return events.events().stream().filter(event -> event.type() == type).count();
    }

    private void assertNoToolExecutionFacts(RecordingAgentEventPublisher events) {
        assertEquals(0, count(events, AgentEventType.TOOL_STARTED));
        assertEquals(0, count(events, AgentEventType.TOOL_SUCCEEDED));
        assertEquals(0, count(events, AgentEventType.TOOL_FAILED));
    }

    private record ToolInput(@NotBlank String query) {
    }

    private record SerializableOutput(String value) {
    }

    private record UnserializableOutput(String value) {
    }

    private static final class CountingTool<O> implements AgentTool<ToolInput, O> {

        private final String name;
        private final Function<ToolInput, O> operation;
        private int executions;

        private CountingTool(String name, Function<ToolInput, O> operation) {
            this.name = name;
            this.operation = operation;
        }

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return new ToolDescriptor<>(
                    name,
                    "Outcome classification fixture",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public O execute(ToolInput input) {
            executions++;
            return operation.apply(input);
        }

        @Override
        public String name() {
            return name;
        }

        private int executions() {
            return executions;
        }
    }
}
