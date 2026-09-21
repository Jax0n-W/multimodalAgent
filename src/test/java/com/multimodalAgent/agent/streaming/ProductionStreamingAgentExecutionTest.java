package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEventKind;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.integration.StreamingAgentExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises the Spring production composition, not a test-assembled AgentRunner. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p83-production;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false"
})
@AutoConfigureWebTestClient
class ProductionStreamingAgentExecutionTest {

    @Autowired
    private StreamingAgentExecutionService execution;

    @SpyBean
    private ExecutionStreamHub hub;

    @Autowired
    private AgentRunRepository runs;

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private OpenAiCompatibleStreamingClient provider;

    @Test
    void authenticatedHttpEntryExecutesTheProductionComposition() {
        String runId = "prod-http-" + UUID.randomUUID();
        when(provider.stream(any())).thenReturn(Flux.just(
                textChunk("hello", OpenAiApi.ChatCompletionFinishReason.STOP),
                OpenAiStreamEvent.Done.INSTANCE
        ));

        webClient.get()
                .uri("/api/agent/runs/{runId}/stream", runId)
                .exchange()
                .expectStatus().isUnauthorized();

        webClient.post()
                .uri("/api/agent/runs")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(Map.of("runId", runId, "message", "hello"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.stopReason").isEqualTo("COMPLETED")
                .jsonPath("$.iterations").isEqualTo(1);

        assertTrue(runs.findByRunId(runId).isPresent());
        assertFalse(hub.isOpen(runId));
    }

    @Test
    void durableAdmissionPrecedesOpeningAndRealProductionEntryStreams() throws Exception {
        String runId = "prod-" + UUID.randomUUID();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch continueExecution = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            opened.countDown();
            if (!continueExecution.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Production execution did not resume");
            }
            return null;
        }).when(hub).openRun(runId);
        when(provider.stream(any())).thenReturn(Flux.just(
                textChunk("Hel", null),
                textChunk("lo", OpenAiApi.ChatCompletionFinishReason.STOP),
                OpenAiStreamEvent.Done.INSTANCE
        ));
        AgentExecutionRequest request = request(runId);
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                () -> execution.execute(request)
        );
        ExecutionStreamSubscription subscription = null;
        Disposable disposable = null;
        try {
            assertTrue(opened.await(10, TimeUnit.SECONDS));
            assertTrue(runs.findByRunId(runId).isPresent());
            subscription = hub.subscribe(runId);
            BlockingQueue<ExecutionStreamEvent> queue = new LinkedBlockingQueue<>();
            disposable = subscription.events().subscribe(queue::offer);

            // A duplicate attempt fails P6 admission and must not close this active stream.
            assertThrows(ExecutionPersistenceException.class, () -> execution.execute(request));
            assertTrue(hub.isOpen(runId));
            assertEquals(1, hub.subscriberCount(runId));
            continueExecution.countDown();

            AgentRunResult result = running.get(20, TimeUnit.SECONDS);
            List<ExecutionStreamEvent> events = take(queue, 6);
            assertEquals(AgentStopReason.COMPLETED, result.stopReason());
            assertEquals(1, result.iterations());
            assertEquals("Hello", result.finalContent());
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), events.stream()
                    .map(ExecutionStreamEvent::streamSequence).toList());
            assertEquals(List.of("Hel", "lo"), events.stream()
                    .filter(event -> event.kind() == ExecutionStreamEventKind.MODEL_DELTA)
                    .map(event -> ((ModelDelta) event.payload()).content()).toList());
            assertEquals(List.of(
                    AgentEventType.RUN_STARTED,
                    AgentEventType.MODEL_STARTED,
                    AgentEventType.MODEL_COMPLETED,
                    AgentEventType.RUN_COMPLETED
            ), events.stream()
                    .filter(event -> event.kind() == ExecutionStreamEventKind.RUNTIME_EVENT)
                    .map(event -> ((RuntimeEventPayload) event.payload()).event().type())
                    .toList());
            assertFalse(hub.isOpen(runId));

            assertThrows(ExecutionPersistenceException.class, () -> execution.execute(request));
            verify(hub, times(1)).openRun(runId);
            assertFalse(hub.isOpen(runId));
        } finally {
            continueExecution.countDown();
            if (disposable != null) {
                disposable.dispose();
            }
            if (subscription != null) {
                subscription.close();
            }
        }
    }

    @Test
    void overflowingProductionSubscriberCannotChangeSuccessfulRun() throws Exception {
        String runId = "prod-overflow-" + UUID.randomUUID();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch continueExecution = new CountDownLatch(1);
        CountDownLatch deltasPublished = new CountDownLatch(1);
        CountDownLatch completeProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            opened.countDown();
            if (!continueExecution.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Production execution did not resume");
            }
            return null;
        }).when(hub).openRun(runId);
        List<OpenAiStreamEvent> chunks = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            chunks.add(textChunk("x", null));
        }
        when(provider.stream(any())).thenReturn(Flux.concat(
                Flux.fromIterable(chunks),
                Flux.defer(() -> {
                    deltasPublished.countDown();
                    try {
                        if (!completeProvider.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Provider was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    return Flux.just(
                            textChunk("", OpenAiApi.ChatCompletionFinishReason.STOP),
                            OpenAiStreamEvent.Done.INSTANCE
                    );
                })
        ));
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                () -> execution.execute(request(runId))
        );
        ExecutionStreamSubscription slow = null;
        try {
            assertTrue(opened.await(10, TimeUnit.SECONDS));
            slow = hub.subscribe(runId);
            // Deliberately never subscribe to slow.events(): its bounded queue must overflow.
            continueExecution.countDown();
            assertTrue(deltasPublished.await(10, TimeUnit.SECONDS));
            assertTrue(slow.isTerminated());
            assertEquals(0, hub.subscriberCount(runId));
            completeProvider.countDown();

            AgentRunResult result = running.get(20, TimeUnit.SECONDS);
            assertEquals(AgentStopReason.COMPLETED, result.stopReason());
            assertEquals(1, result.iterations());
            assertEquals(300, result.finalContent().length());
        } finally {
            continueExecution.countDown();
            completeProvider.countDown();
            if (slow != null) {
                slow.close();
            }
        }
    }

    private AgentExecutionRequest request(String runId) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        "session-" + runId,
                        List.of(AgentMessage.user("hello")),
                        3,
                        Set.of(),
                        Set.of()
                ),
                "request-" + runId,
                2L
        );
    }

    private List<ExecutionStreamEvent> take(
            BlockingQueue<ExecutionStreamEvent> queue,
            int count
    ) throws InterruptedException {
        List<ExecutionStreamEvent> events = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ExecutionStreamEvent event = queue.poll(10, TimeUnit.SECONDS);
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
                "response-production",
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
