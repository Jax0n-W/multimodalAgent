package com.multimodalAgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.runtime.control.ExecutionControl;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.event.ToolPolicyEvaluatedEvent;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooperativeCancellationTest {

    @Test
    void beforeModelPreventsProviderStartAndDoesNotCountAnIteration() {
        ExecutionControl control = ExecutionControl.create();
        control.requestCancel();
        AtomicInteger modelCalls = new AtomicInteger();
        List<AgentEvent> events = new ArrayList<>();
        AgentRunResult result = execute(request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.finalAnswer("unexpected");
        }, List.of(), Set.of(), control, events, null);

        assertEquals(AgentStopReason.CANCELLED, result.stopReason());
        assertEquals(0, result.iterations());
        assertEquals(0, modelCalls.get());
        assertEquals(List.of(AgentEventType.RUN_STARTED, AgentEventType.RUN_STOPPED), types(events));
    }

    @Test
    void afterModelPreservesCompletionAndUsageButBlocksToolInterpretation() throws Exception {
        ExecutionControl control = ExecutionControl.create();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentModel model = request -> {
            started.countDown();
            await(release);
            return new ModelTurn(
                    ModelFinishReason.TOOL_CALLS, "",
                    List.of(call("call-1", "one")), new TokenUsage(7, 3)
            );
        };
        AtomicInteger toolCalls = new AtomicInteger();
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(model, List.of(tool("one", input -> {
                    toolCalls.incrementAndGet();
                    return "unexpected";
                })), Set.of("one"), control, events, null));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED, control.requestCancel());
            release.countDown();
            AgentRunResult result = running.get(5, TimeUnit.SECONDS);
            assertEquals(AgentStopReason.CANCELLED, result.stopReason());
            assertEquals(1, result.iterations());
            assertEquals(new TokenUsage(7, 3), result.tokenUsage());
            assertEquals(0, toolCalls.get());
            assertTrue(types(events).contains(AgentEventType.MODEL_COMPLETED));
            assertFalse(types(events).contains(AgentEventType.TOOL_REQUESTED));
            assertFalse(types(events).contains(AgentEventType.RUN_COMPLETED));
        } finally {
            release.countDown();
        }
    }

    @Test
    void startedModelFailureWinsOverInFlightCancellation() throws Exception {
        ExecutionControl control = ExecutionControl.create();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(request -> {
                    started.countDown();
                    await(release);
                    throw new IllegalStateException("provider failed");
                }, List.of(), Set.of(), control, events, null));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            control.requestCancel();
            release.countDown();
            assertEquals(AgentStopReason.MODEL_ERROR,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertTrue(types(events).contains(AgentEventType.MODEL_FAILED));
        } finally {
            release.countDown();
        }
    }

    @Test
    void beforeToolIsAfterAllowAndBeforeSideEffect() {
        ExecutionControl control = ExecutionControl.create();
        List<AgentEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AgentRunResult result = execute(
                request -> ModelTurn.toolCall(call("call-1", "one")),
                List.of(tool("one", input -> {
                    calls.incrementAndGet();
                    return "unexpected";
                })),
                Set.of("one"), control, events,
                event -> {
                    if (event instanceof ToolPolicyEvaluatedEvent evaluated
                            && evaluated.decision() == ToolPolicyDecisionType.ALLOW) {
                        control.requestCancel();
                    }
                }
        );
        assertEquals(AgentStopReason.CANCELLED, result.stopReason());
        assertEquals(0, calls.get());
        assertTrue(types(events).contains(AgentEventType.TOOL_VALIDATED));
        assertTrue(types(events).contains(AgentEventType.TOOL_POLICY_EVALUATED));
        assertFalse(types(events).contains(AgentEventType.TOOL_STARTED));
        assertFalse(types(events).contains(AgentEventType.TOOL_FAILED));
    }

    @Test
    void afterFirstToolPreservesSuccessAndStopsLaterToolsAndModels() throws Exception {
        ExecutionControl control = ExecutionControl.create();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondToolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentModel model = request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.toolCall(call("call-1", "one"), call("call-2", "two"));
        };
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(model, List.of(
                        tool("one", input -> {
                            toolStarted.countDown();
                            await(release);
                            return "first result";
                        }),
                        tool("two", input -> {
                            secondToolCalls.incrementAndGet();
                            return "second result";
                        })
                ), Set.of("one", "two"), control, events, null));
        try {
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
            control.requestCancel();
            release.countDown();
            AgentRunResult result = running.get(5, TimeUnit.SECONDS);
            assertEquals(AgentStopReason.CANCELLED, result.stopReason());
            assertEquals(List.of("one"), result.toolsUsed());
            assertTrue(result.messages().stream().anyMatch(
                    message -> "first result".equals(message.content())
            ));
            assertEquals(1, modelCalls.get());
            assertEquals(0, secondToolCalls.get());
            assertTrue(types(events).contains(AgentEventType.TOOL_SUCCEEDED));
            assertEquals(0, types(events).stream()
                    .filter(type -> type == AgentEventType.TOOL_FAILED).count());
        } finally {
            release.countDown();
        }
    }

    @Test
    void startedToolFailureWinsOverInFlightCancellation() throws Exception {
        ExecutionControl control = ExecutionControl.create();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(request -> ModelTurn.toolCall(call("call-1", "one")),
                        List.of(tool("one", input -> {
                            toolStarted.countDown();
                            await(release);
                            throw new IllegalStateException("tool failed");
                        })),
                        Set.of("one"), control, events, null));
        try {
            assertTrue(toolStarted.await(5, TimeUnit.SECONDS));
            control.requestCancel();
            release.countDown();
            assertEquals(AgentStopReason.TOOL_ERROR,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertTrue(types(events).contains(AgentEventType.TOOL_FAILED));
        } finally {
            release.countDown();
        }
    }

    @Test
    void modelPostBoundaryFailureWinsOverCancellation() {
        ExecutionControl control = ExecutionControl.create();
        List<AgentEvent> events = new ArrayList<>();
        RuntimeMiddleware failingPostcheck = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                next.proceed();
                control.requestCancel();
                throw new IllegalStateException("post-model boundary failed");
            }
        };
        AgentRunResult result = execute(
                request -> ModelTurn.finalAnswer("completed model"),
                List.of(), Set.of(), control, events, null,
                new RuntimeMiddlewareChain(List.of(failingPostcheck))
        );
        assertEquals(AgentStopReason.INTERNAL_ERROR, result.stopReason());
        assertTrue(types(events).contains(AgentEventType.MODEL_COMPLETED));
        assertFalse(events.stream().anyMatch(event ->
                event instanceof com.multimodalAgent.agent.runtime.event.RunStoppedEvent stopped
                        && stopped.stopReason() == AgentStopReason.CANCELLED));
    }

    @Test
    void toolPostBoundaryFailureWinsOverCancellation() {
        ExecutionControl control = ExecutionControl.create();
        List<AgentEvent> events = new ArrayList<>();
        RuntimeMiddleware failingPostcheck = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                next.proceed();
                control.requestCancel();
                throw new IllegalStateException("post-tool boundary failed");
            }
        };
        AgentRunResult result = execute(
                request -> ModelTurn.toolCall(call("call-1", "one")),
                List.of(tool("one", input -> "completed tool")),
                Set.of("one"), control, events, null,
                new RuntimeMiddlewareChain(List.of(failingPostcheck))
        );
        assertEquals(AgentStopReason.INTERNAL_ERROR, result.stopReason());
        assertTrue(types(events).contains(AgentEventType.TOOL_SUCCEEDED));
        assertFalse(events.stream().anyMatch(event ->
                event instanceof com.multimodalAgent.agent.runtime.event.RunStoppedEvent stopped
                        && stopped.stopReason() == AgentStopReason.CANCELLED));
    }

    @Test
    void acceptedCancelBeforeNormalTerminalDecisionProducesCancelled() throws Exception {
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(
                new ExecutionStreamPublisher(new ExecutionStreamHub())
        );
        LocalExecutionControlRegistry.Entry control = registry.create("cancel-run");
        registry.register(control);
        CountDownLatch modelCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(request -> ModelTurn.finalAnswer("answer"),
                        List.of(), Set.of(), control, events, event -> {
                            if (event instanceof ModelCompletedEvent) {
                                modelCompleted.countDown();
                                await(release);
                            }
                        }));
        try {
            assertTrue(modelCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED, registry.requestCancel("cancel-run"));
            release.countDown();
            assertEquals(AgentStopReason.CANCELLED,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertFalse(types(events).contains(AgentEventType.RUN_COMPLETED));
        } finally {
            release.countDown();
            registry.close(control);
        }
    }

    @Test
    void sealedNormalTerminalDecisionRejectsLaterCancel() throws Exception {
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(
                new ExecutionStreamPublisher(new ExecutionStreamHub())
        );
        LocalExecutionControlRegistry.Entry control = registry.create("cancel-run");
        registry.register(control);
        CountDownLatch runCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                execute(request -> ModelTurn.finalAnswer("answer"),
                        List.of(), Set.of(), control, events, event -> {
                            if (event.type() == AgentEventType.RUN_COMPLETED) {
                                runCompleted.countDown();
                                await(release);
                            }
                        }));
        try {
            assertTrue(runCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                    registry.requestCancel("cancel-run"));
            release.countDown();
            assertEquals(AgentStopReason.COMPLETED,
                    running.get(5, TimeUnit.SECONDS).stopReason());
        } finally {
            release.countDown();
            registry.close(control);
        }
    }

    private AgentRunResult execute(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            Set<String> allowed,
            ExecutionControl control,
            List<AgentEvent> events,
            java.util.function.Consumer<AgentEvent> observer
    ) {
        return execute(model, tools, allowed, control, events, observer,
                RuntimeMiddlewareChain.empty());
    }

    private AgentRunResult execute(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            Set<String> allowed,
            ExecutionControl control,
            List<AgentEvent> events,
            java.util.function.Consumer<AgentEvent> observer,
            RuntimeMiddlewareChain middlewareChain
    ) {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        mapper, Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(), mapper
        );
        AgentRunner runner = new AgentRunner(
                model, executor, TestModelToolDefinitionProjector.INSTANCE,
                event -> {
                    events.add(event);
                    if (observer != null) {
                        observer.accept(event);
                    }
                }
        );
        AgentRunSpec spec = new AgentRunSpec(
                "cancel-run", "cancel-session",
                List.of(AgentMessage.user("question")), 3, allowed, Set.of()
        );
        AgentRuntimeContext context = new AgentRuntimeContext(
                spec.runId(), null, spec.sessionId(), null, null,
                control, new RuntimeAttributes()
        );
        return runner.run(spec, context, middlewareChain);
    }

    private ToolCall call(String id, String name) {
        return new ToolCall(id, name, Map.of("query", "question"));
    }

    private AgentTool<KnowledgeSearchInput, String> tool(
            String name,
            Function<KnowledgeSearchInput, String> action
    ) {
        return new AgentTool<>() {
            private final ToolDescriptor<KnowledgeSearchInput> descriptor =
                    new ToolDescriptor<>(
                            name, "Test tool", KnowledgeSearchInput.class,
                            ToolRisk.LOW, true, true, false
                    );

            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return descriptor;
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                return action.apply(input);
            }
        };
    }

    private List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test operation was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
