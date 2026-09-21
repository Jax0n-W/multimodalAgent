package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.ModelDeltaObserver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingModelInvocationScopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiCompatibleStreamingOptions options =
            new OpenAiCompatibleStreamingOptions("test-provider", "test-model", 0.0, 128);

    @Test
    void concurrentInvocationsKeepObserversIsolatedAcrossReactorThreadHop() throws Exception {
        CountDownLatch bothSubscribed = new CountDownLatch(2);
        OpenAiCompatibleStreamingClient client = request -> Flux.defer(() -> {
            String marker = request.messages().get(0).content().toString();
            bothSubscribed.countDown();
            await(bothSubscribed);
            return Flux.just(
                    textChunk(marker, OpenAiApi.ChatCompletionFinishReason.STOP),
                    OpenAiStreamEvent.Done.INSTANCE
            );
        }).publishOn(Schedulers.parallel());
        StreamingModelInvocationScope scope = new StreamingModelInvocationScope();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(client, scope);
        List<ModelDelta> observedA = new CopyOnWriteArrayList<>();
        List<ModelDelta> observedB = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> callerA = new AtomicReference<>();
        AtomicReference<Thread> callerB = new AtomicReference<>();
        AtomicReference<Thread> callbackA = new AtomicReference<>();
        AtomicReference<Thread> callbackB = new AtomicReference<>();
        AgentRuntimeContext contextA = context("A");
        AgentRuntimeContext contextB = context("B");
        StreamingModelInvocationScope.registerObserver(contextA, delta -> {
            callbackA.set(Thread.currentThread());
            observedA.add(delta);
        });
        StreamingModelInvocationScope.registerObserver(contextB, delta -> {
            callbackB.set(Thread.currentThread());
            observedB.add(delta);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ModelTurn> resultA = executor.submit(() -> {
                callerA.set(Thread.currentThread());
                return invoke(scope, contextA, adapter, "A");
            });
            Future<ModelTurn> resultB = executor.submit(() -> {
                callerB.set(Thread.currentThread());
                return invoke(scope, contextB, adapter, "B");
            });

            assertEquals("A", resultA.get(10, TimeUnit.SECONDS).content());
            assertEquals("B", resultB.get(10, TimeUnit.SECONDS).content());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(List.of(new ModelDelta(1, "A")), observedA);
        assertEquals(List.of(new ModelDelta(1, "B")), observedB);
        assertNotSame(callerA.get(), callbackA.get());
        assertNotSame(callerB.get(), callbackB.get());
    }

    @Test
    void successAndFailureBothClearScopeBeforeCallerThreadReuse() {
        OpenAiCompatibleStreamingClient client = request -> {
            String marker = request.messages().get(0).content().toString();
            if (marker.equals("failure")) {
                return Flux.concat(
                        Flux.just(textChunk("partial", null)),
                        Flux.error(new IllegalStateException("connection reset"))
                );
            }
            return Flux.just(
                    textChunk(marker, OpenAiApi.ChatCompletionFinishReason.STOP),
                    OpenAiStreamEvent.Done.INSTANCE
            );
        };
        StreamingModelInvocationScope scope = new StreamingModelInvocationScope();
        OpenAiCompatibleStreamingAgentModelAdapter adapter = adapter(client, scope);
        List<ModelDelta> observed = new CopyOnWriteArrayList<>();
        AgentRuntimeContext success = context("success");
        AgentRuntimeContext failure = context("failure");
        StreamingModelInvocationScope.registerObserver(success, observed::add);
        StreamingModelInvocationScope.registerObserver(failure, observed::add);

        assertEquals("success", invoke(scope, success, adapter, "success").content());
        assertFalse(scope.capture().enabled());
        assertEquals("without-scope", adapter.generate(request("without-scope")).content());
        assertEquals(List.of(new ModelDelta(1, "success")), observed);

        assertThrows(
                SpringAiModelAdapterException.class,
                () -> invoke(scope, failure, adapter, "failure")
        );
        assertFalse(scope.capture().enabled());
        assertEquals("after-failure", adapter.generate(request("after-failure")).content());
        assertEquals(
                List.of(new ModelDelta(1, "success"), new ModelDelta(1, "partial")),
                observed
        );
    }

    @Test
    void nestedInvocationRestoresOuterObservation() {
        StreamingModelInvocationScope scope = new StreamingModelInvocationScope();
        AgentRuntimeContext outer = context("outer");
        AgentRuntimeContext inner = context("inner");
        ModelDeltaObserver outerObserver = ignored -> {
        };
        ModelDeltaObserver innerObserver = ignored -> {
        };
        StreamingModelInvocationScope.registerObserver(outer, outerObserver);
        StreamingModelInvocationScope.registerObserver(inner, innerObserver);

        scope.aroundModelCall(outer, new ModelCallMetadata(1, 1), () -> {
            assertSame(outerObserver, scope.capture().observer());
            scope.aroundModelCall(inner, new ModelCallMetadata(2, 1), () -> {
                assertSame(innerObserver, scope.capture().observer());
                return ModelTurn.finalAnswer("inner");
            });
            assertSame(outerObserver, scope.capture().observer());
            return ModelTurn.finalAnswer("outer");
        });

        assertFalse(scope.capture().enabled());
    }

    private OpenAiCompatibleStreamingAgentModelAdapter adapter(
            OpenAiCompatibleStreamingClient client,
            StreamingModelInvocationScope scope
    ) {
        return new OpenAiCompatibleStreamingAgentModelAdapter(
                client,
                options,
                objectMapper,
                scope
        );
    }

    private ModelTurn invoke(
            StreamingModelInvocationScope scope,
            AgentRuntimeContext context,
            OpenAiCompatibleStreamingAgentModelAdapter adapter,
            String marker
    ) {
        return scope.aroundModelCall(
                context,
                new ModelCallMetadata(1, 1),
                () -> adapter.generate(request(marker))
        );
    }

    private AgentRuntimeContext context(String marker) {
        return AgentRuntimeContext.minimal("run-" + marker, "session-" + marker);
    }

    private AgentModelRequest request(String marker) {
        return new AgentModelRequest(List.of(AgentMessage.user(marker)), List.of());
    }

    private OpenAiStreamEvent textChunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
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

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent invocations did not overlap");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrent invocation test interrupted", exception);
        }
    }
}
