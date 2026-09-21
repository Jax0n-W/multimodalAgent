package com.multimodalAgent.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingAgentModelAdapter;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingOptions;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEventKind;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.bridge.AgentEventStreamBridge;
import com.multimodalAgent.agent.streaming.bridge.ModelDeltaStreamBridge;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UnifiedExecutionStreamIntegrationTest {

    @Test
    void subscriberOverflowCannotRewriteSuccessfulAgentRun() {
        String runId = "run-overflow-isolation";
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutionStreamHub hub = new ExecutionStreamHub(1);
        ExecutionStreamPublisher streamPublisher = new ExecutionStreamPublisher(hub);
        hub.openRun(runId);
        ExecutionStreamSubscription nonConsumingSubscriber = hub.subscribe(runId);
        AgentRunner runner = new AgentRunner(
                ignored -> ModelTurn.finalAnswer("completed"),
                emptyToolExecutor(objectMapper),
                ignored -> {
                    throw new AssertionError("No tool definition should be projected");
                },
                new AgentEventStreamBridge(streamPublisher)
        );
        AgentRunSpec spec = new AgentRunSpec(
                runId,
                "session-overflow-isolation",
                List.of(AgentMessage.user("hello")),
                2,
                Set.of(),
                Set.of()
        );
        try {
            AgentRunResult result = runner.run(spec);

            assertEquals(AgentStopReason.COMPLETED, result.stopReason());
            assertEquals("completed", result.finalContent());
            assertEquals(1, result.iterations());
            assertEquals(0, hub.subscriberCount(runId));
        } finally {
            nonConsumingSubscriber.close();
            hub.closeRun(runId);
        }
    }

    @Test
    void runtimeFactsAndProviderDeltasShareOneLiveOrder() throws Exception {
        String runId = "run-unified-e2e";
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutionStreamHub hub = new ExecutionStreamHub(32);
        ExecutionStreamPublisher streamPublisher = new ExecutionStreamPublisher(
                hub,
                Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC)
        );
        hub.openRun(runId);
        ExecutionStreamSubscription subscription = hub.subscribe(runId);
        BlockingQueue<ExecutionStreamEvent> observed = new LinkedBlockingQueue<>();
        Disposable disposable = subscription.events().subscribe(observed::offer);
        AtomicInteger providerCalls = new AtomicInteger();
        StreamingModelInvocationScope invocationScope = new StreamingModelInvocationScope();
        OpenAiCompatibleStreamingAgentModelAdapter model =
                new OpenAiCompatibleStreamingAgentModelAdapter(
                        ignored -> {
                            providerCalls.incrementAndGet();
                            return Flux.just(
                                    textChunk("Hel", null),
                                    textChunk(
                                            "lo",
                                            OpenAiApi.ChatCompletionFinishReason.STOP
                                    ),
                                    OpenAiStreamEvent.Done.INSTANCE
                            );
                        },
                        new OpenAiCompatibleStreamingOptions(
                                "deterministic-provider",
                                "deterministic-model",
                                0.0,
                                128
                        ),
                        objectMapper,
                        invocationScope
                );
        AgentRunner runner = new AgentRunner(
                model,
                emptyToolExecutor(objectMapper),
                ignored -> {
                    throw new AssertionError("No tool definition should be projected");
                },
                new AgentEventStreamBridge(streamPublisher)
        );
        AgentRunSpec spec = new AgentRunSpec(
                runId,
                "session-unified-e2e",
                List.of(AgentMessage.user("hello")),
                3,
                Set.of(),
                Set.of()
        );
        AgentRuntimeContext context = AgentRuntimeContext.minimal(runId, spec.sessionId());
        StreamingModelInvocationScope.registerObserver(
                context,
                new ModelDeltaStreamBridge(runId, streamPublisher)
        );
        try {
            AgentRunResult result = runner.run(
                    spec,
                    context,
                    new RuntimeMiddlewareChain(List.of(invocationScope))
            );

            List<ExecutionStreamEvent> events = take(observed, 6);
            assertEquals(AgentStopReason.COMPLETED, result.stopReason());
            assertEquals(1, result.iterations());
            assertEquals(1, providerCalls.get());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), events.stream()
                    .map(ExecutionStreamEvent::streamSequence)
                    .toList());
            assertEquals(
                    List.of(
                            ExecutionStreamEventKind.RUNTIME_EVENT,
                            ExecutionStreamEventKind.RUNTIME_EVENT,
                            ExecutionStreamEventKind.MODEL_DELTA,
                            ExecutionStreamEventKind.MODEL_DELTA,
                            ExecutionStreamEventKind.RUNTIME_EVENT,
                            ExecutionStreamEventKind.RUNTIME_EVENT
                    ),
                    events.stream().map(ExecutionStreamEvent::kind).toList()
            );
            assertEquals(
                    List.of(
                            AgentEventType.RUN_STARTED,
                            AgentEventType.MODEL_STARTED,
                            AgentEventType.MODEL_COMPLETED,
                            AgentEventType.RUN_COMPLETED
                    ),
                    events.stream()
                            .filter(event -> event.kind()
                                    == ExecutionStreamEventKind.RUNTIME_EVENT)
                            .map(event -> ((RuntimeEventPayload) event.payload()).event().type())
                            .toList()
            );
            assertEquals(
                    List.of("Hel", "lo"),
                    events.stream()
                            .filter(event -> event.kind()
                                    == ExecutionStreamEventKind.MODEL_DELTA)
                            .map(event -> ((ModelDelta) event.payload()).content())
                            .toList()
            );
            assertEquals(
                    List.of(1L, 2L, 3L, 4L),
                    events.stream()
                            .filter(event -> event.kind()
                                    == ExecutionStreamEventKind.RUNTIME_EVENT)
                            .map(event -> ((RuntimeEventPayload) event.payload())
                                    .event().sequence())
                            .toList()
            );
        } finally {
            disposable.dispose();
            subscription.close();
            hub.closeRun(runId);
        }
    }

    private ToolExecutor emptyToolExecutor(ObjectMapper objectMapper) {
        return new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
    }

    private List<ExecutionStreamEvent> take(
            BlockingQueue<ExecutionStreamEvent> queue,
            int count
    ) throws InterruptedException {
        List<ExecutionStreamEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ExecutionStreamEvent event = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            events.add(event);
        }
        return events;
    }

    private OpenAiStreamEvent textChunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason,
                        0,
                        message,
                        null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "response-unified",
                List.of(choice),
                1L,
                "deterministic-model",
                null,
                null,
                "chat.completion.chunk",
                null
        ));
    }
}
