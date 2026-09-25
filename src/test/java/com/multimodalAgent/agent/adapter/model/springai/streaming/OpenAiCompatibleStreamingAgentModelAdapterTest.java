package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsageStatus;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelGateway;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetrySink;
import com.multimodalAgent.agent.runtime.model.gateway.ModelProviderException;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import com.multimodalAgent.agent.stream.ModelDelta;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleStreamingAgentModelAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiCompatibleStreamingOptions options =
            new OpenAiCompatibleStreamingOptions("test-provider", "test-model", 0.0, 128);

    @Test
    void emitsProviderOrderedDeltasAndReturnsOneStopTurn() {
        List<ModelDelta> deltas = new ArrayList<>();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.just(
                        textChunk("Hello", null),
                        textChunk(" ", null),
                        textChunk("world", OpenAiApi.ChatCompletionFinishReason.STOP),
                        OpenAiStreamEvent.Done.INSTANCE
                ),
                deltas::add,
                () -> 3
        );

        ModelTurn turn = adapter.generate(request());

        assertEquals(ModelFinishReason.STOP, turn.finishReason());
        assertEquals("Hello world", turn.content());
        assertEquals(
                List.of(
                        new ModelDelta(3, "Hello"),
                        new ModelDelta(3, " "),
                        new ModelDelta(3, "world")
                ),
                deltas
        );
    }

    @Test
    void observerFailureIsIsolatedFromSuccessfulModelTurn() {
        AtomicInteger observationCalls = new AtomicInteger();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.just(
                        textChunk("still ", null),
                        textChunk("works", OpenAiApi.ChatCompletionFinishReason.STOP),
                        OpenAiStreamEvent.Done.INSTANCE
                ),
                delta -> {
                    observationCalls.incrementAndGet();
                    throw new IllegalStateException("observer unavailable");
                },
                () -> 1
        );

        ModelTurn turn = adapter.generate(request());

        assertEquals("still works", turn.content());
        assertEquals(ModelFinishReason.STOP, turn.finishReason());
        assertEquals(1, observationCalls.get());
    }

    @Test
    void providerFailureAfterPartialDeltasFailsTheInvocation() {
        List<ModelDelta> deltas = new ArrayList<>();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.concat(
                        Flux.just(textChunk("partial", null), textChunk(" text", null)),
                        Flux.error(new IllegalStateException("connection reset"))
                ),
                deltas::add,
                () -> 1
        );

        assertThrows(SpringAiModelAdapterException.class, () -> adapter.generate(request()));
        assertEquals(
                List.of(new ModelDelta(1, "partial"), new ModelDelta(1, " text")),
                deltas
        );
    }

    @Test
    void incompleteStreamNeverReturnsPartialTextAsSuccess() {
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.just(textChunk("partial", null)),
                delta -> {
                },
                () -> 1
        );

        assertThrows(SpringAiModelAdapterException.class, () -> adapter.generate(request()));
    }

    @Test
    void preservesProviderToolCallIdAndWaitsForCompleteArguments() {
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.just(
                        toolChunk(fragment(0, "call-abc", "lookup", "{\"query\":"), null),
                        toolChunk(fragment(0, null, null, "\"Redis\"}"),
                                OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS),
                        OpenAiStreamEvent.Done.INSTANCE
                ),
                delta -> {
                },
                () -> 1
        );

        ModelTurn turn = adapter.generate(request());

        assertEquals(ModelFinishReason.TOOL_CALLS, turn.finishReason());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call-abc", turn.toolCalls().get(0).id());
        assertEquals("lookup", turn.toolCalls().get(0).name());
        assertEquals("Redis", turn.toolCalls().get(0).arguments().get("query"));
    }

    @Test
    void mapsMessagesToolsAndStreamingOptionsWithoutProviderSideExecution() {
        AtomicReference<OpenAiApi.ChatCompletionRequest> captured = new AtomicReference<>();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = new OpenAiCompatibleStreamingAgentModelAdapter(
                request -> {
                    captured.set(request);
                    return Flux.just(
                            textChunk("done", OpenAiApi.ChatCompletionFinishReason.STOP),
                            OpenAiStreamEvent.Done.INSTANCE
                    );
                },
                options,
                objectMapper
        );
        ToolCall priorCall = new ToolCall("call-prior", "lookup", Map.of("query", "Redis"));
        AgentModelRequest request = new AgentModelRequest(
                List.of(
                        AgentMessage.system("system"),
                        AgentMessage.user("question"),
                        AgentMessage.assistantToolCalls(List.of(priorCall)),
                        AgentMessage.toolResult("call-prior", "lookup", "result")
                ),
                List.of(new ModelToolDefinition(
                        "lookup",
                        "Search knowledge",
                        schema()
                ))
        );

        adapter.generate(request);

        OpenAiApi.ChatCompletionRequest providerRequest = captured.get();
        assertTrue(providerRequest.stream());
        assertTrue(providerRequest.streamOptions().includeUsage());
        assertEquals("test-model", providerRequest.model());
        assertEquals(1, providerRequest.n());
        assertEquals(4, providerRequest.messages().size());
        assertEquals("call-prior", providerRequest.messages().get(2).toolCalls().get(0).id());
        assertEquals("call-prior", providerRequest.messages().get(3).toolCallId());
        assertEquals(1, providerRequest.tools().size());
        assertEquals("lookup", providerRequest.tools().get(0).getFunction().getName());
    }

    @Test
    void manyChunksRemainOneAgentModelInvocationAndOneIteration() {
        AtomicInteger providerCalls = new AtomicInteger();
        List<ModelDelta> deltas = new ArrayList<>();
        List<OpenAiStreamEvent> events = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            events.add(textChunk("x", null));
        }
        events.add(textChunk("", OpenAiApi.ChatCompletionFinishReason.STOP));
        events.add(OpenAiStreamEvent.Done.INSTANCE);
        OpenAiCompatibleStreamingClient client = ignored -> {
            providerCalls.incrementAndGet();
            return Flux.fromIterable(events);
        };
        StreamingModelInvocationScope scope = new StreamingModelInvocationScope();
        OpenAiCompatibleStreamingAgentModelAdapter model =
                new OpenAiCompatibleStreamingAgentModelAdapter(
                        client,
                        options,
                        objectMapper,
                        scope
                );
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AgentRunner runner = new AgentRunner(
                model,
                emptyToolExecutor(),
                descriptor -> {
                    throw new AssertionError("No tool definition should be projected");
                },
                publisher
        );
        AgentRunSpec spec = new AgentRunSpec(
                "run-stream",
                "session-stream",
                List.of(AgentMessage.user("stream")),
                3,
                Set.of(),
                Set.of()
        );
        AgentRuntimeContext context = AgentRuntimeContext.minimal(
                spec.runId(),
                spec.sessionId()
        );
        StreamingModelInvocationScope.registerObserver(context, deltas::add);

        AgentRunResult result = runner.run(
                spec,
                context,
                new RuntimeMiddlewareChain(List.of(scope))
        );

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, result.iterations());
        assertEquals(1, providerCalls.get());
        assertEquals(100, deltas.size());
        assertTrue(deltas.stream().allMatch(delta -> delta.iteration() == 1));
        assertEquals(1, publisher.events().stream()
                .filter(event -> event.type() == AgentEventType.MODEL_STARTED)
                .count());
        assertEquals(1, publisher.events().stream()
                .filter(event -> event.type() == AgentEventType.MODEL_COMPLETED)
                .count());
        assertEquals(
                TokenUsageStatus.UNKNOWN_OR_INCOMPLETE,
                publisher.events().stream()
                        .filter(event -> event.type() == AgentEventType.MODEL_COMPLETED)
                        .map(event -> (com.multimodalAgent.agent.runtime.event.ModelCompletedEvent) event)
                        .findFirst()
                        .orElseThrow()
                        .tokenUsageStatus()
        );
        assertFalse(publisher.events().stream()
                .anyMatch(event -> event.type() == AgentEventType.MODEL_FAILED));
    }

    @Test
    void idleTimeoutIsTypedAndNeverReturnsAnIncompleteToolCall() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.concat(
                        Flux.just(toolChunk(
                                fragment(0, "call-partial", "lookup", "{\"query\":"),
                                null
                        )),
                        Flux.defer(() -> {
                            subscribed.countDown();
                            return Flux.never();
                        })
                ),
                delta -> { },
                () -> 1
        );
        CompletableFuture<ModelTurn> invocation = CompletableFuture.supplyAsync(
                () -> adapter.generate(request(), new ModelTimeoutPolicy(
                        Duration.ofSeconds(2), Duration.ofMillis(50)
                ))
        );
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));

        CompletionException failure = assertThrows(
                CompletionException.class,
                invocation::join
        );
        assertTrue(failure.getCause() instanceof ModelProviderException);
        assertEquals(
                ModelFailureKind.TIMEOUT,
                ((ModelProviderException) failure.getCause()).failureKind()
        );
        assertTrue(failure.getCause().getMessage().contains("idle timeout"));
    }

    @Test
    void invocationTimeoutWinsWhileChunksKeepTheStreamNonIdle() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.defer(() -> {
                    subscribed.countDown();
                    return Flux.interval(Duration.ZERO, Duration.ofMillis(20))
                            .map(index -> textChunk("x", null));
                }),
                delta -> { },
                () -> 1
        );
        CompletableFuture<ModelTurn> invocation = CompletableFuture.supplyAsync(
                () -> adapter.generate(request(), new ModelTimeoutPolicy(
                        Duration.ofMillis(300), Duration.ofMillis(250)
                ))
        );
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));

        CompletionException failure = assertThrows(
                CompletionException.class,
                invocation::join
        );
        assertTrue(failure.getCause() instanceof ModelProviderException);
        assertEquals(
                ModelFailureKind.TIMEOUT,
                ((ModelProviderException) failure.getCause()).failureKind()
        );
        assertTrue(failure.getCause().getMessage().contains("invocation timeout"));
    }

    @Test
    void idleTimeoutCannotExposeAPartialToolCallToToolExecutor() {
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(
                ignored -> Flux.concat(
                        Flux.just(toolChunk(
                                fragment(0, "call-partial", "lookup", "{\"query\":"),
                                null
                        )),
                        Flux.never()
                ),
                delta -> { },
                () -> 1
        );
        ModelGateway gateway = new ModelGateway(
                adapter,
                new ModelIdentity("test-provider", "test-model"),
                new ModelTimeoutPolicy(Duration.ofSeconds(2), Duration.ofMillis(50)),
                ModelInvocationTelemetrySink.NOOP
        );
        AtomicInteger executions = new AtomicInteger();
        AgentTool<KnowledgeSearchInput, String> tool = new AgentTool<>() {
            private final ToolDescriptor<KnowledgeSearchInput> descriptor =
                    new ToolDescriptor<>(
                            "lookup", "lookup", KnowledgeSearchInput.class,
                            ToolRisk.LOW, true, true, false
                    );

            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return descriptor;
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                executions.incrementAndGet();
                return "unexpected";
            }
        };
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AgentRunner runner = new AgentRunner(
                gateway,
                toolExecutor(List.of(tool)),
                descriptor -> new ModelToolDefinition(
                        descriptor.name(), descriptor.description(), schema()
                ),
                publisher
        );
        AgentRunResult result = runner.run(new AgentRunSpec(
                "run-partial-timeout", "session-partial-timeout",
                List.of(AgentMessage.user("lookup")), 1, Set.of("lookup"), Set.of()
        ));

        assertEquals(AgentStopReason.MODEL_TIMEOUT, result.stopReason());
        assertEquals(0, executions.get());
        assertFalse(publisher.events().stream().anyMatch(event ->
                event.type() == AgentEventType.TOOL_REQUESTED
                        || event.type() == AgentEventType.TOOL_STARTED));
    }

    private OpenAiCompatibleStreamingAgentModelAdapter adapter(
            OpenAiCompatibleStreamingClient client,
            com.multimodalAgent.agent.stream.ModelDeltaObserver observer,
            java.util.function.IntSupplier iterationSupplier
    ) {
        return new OpenAiCompatibleStreamingAgentModelAdapter(
                client,
                options,
                objectMapper,
                observer,
                iterationSupplier
        );
    }

    private AgentModelRequest request() {
        return new AgentModelRequest(List.of(AgentMessage.user("hello")), List.of());
    }

    private ToolExecutor emptyToolExecutor() {
        return toolExecutor(List.of());
    }

    private ToolExecutor toolExecutor(List<? extends AgentTool<?, ?>> tools) {
        return new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
    }

    private Map<String, Object> schema() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    private OpenAiStreamEvent textChunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        return chunk(new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        ), finishReason);
    }

    private OpenAiStreamEvent toolChunk(
            OpenAiApi.ChatCompletionMessage.ToolCall fragment,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        return chunk(new OpenAiApi.ChatCompletionMessage(
                null,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null,
                null,
                List.of(fragment),
                null,
                null,
                null
        ), finishReason);
    }

    private OpenAiApi.ChatCompletionMessage.ToolCall fragment(
            int index,
            String id,
            String name,
            String arguments
    ) {
        return new OpenAiApi.ChatCompletionMessage.ToolCall(
                index,
                id,
                id == null ? null : "function",
                new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(name, arguments)
        );
    }

    private OpenAiStreamEvent chunk(
            OpenAiApi.ChatCompletionMessage delta,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason,
                        0,
                        delta,
                        null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-1",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                null
        ));
    }
}
