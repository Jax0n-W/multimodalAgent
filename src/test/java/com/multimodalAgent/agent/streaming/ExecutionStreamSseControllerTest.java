package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.controller.ExecutionStreamSseController;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStreamSseControllerTest {

    @Test
    void webFluxEndpointIsLiveOnlyAndClientCancellationRemovesSubscription() throws Exception {
        String runId = "run-sse-webflux";
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(
                hub,
                Clock.fixed(Instant.parse("2026-09-21T13:00:00Z"), ZoneOffset.UTC)
        );
        hub.openRun(runId);
        WebTestClient client = WebTestClient.bindToController(
                new ExecutionStreamSseController(hub)
        ).build();

        CompletableFuture<FluxExchangeResult<String>> pendingResponse =
                CompletableFuture.supplyAsync(() -> client.get()
                .uri("/api/agent/runs/{runId}/stream", runId)
                        .header("Last-Event-ID", "500")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .exchange()
                        .expectStatus().isOk()
                        .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                        .returnResult(String.class));
        awaitSubscriber(hub, runId);
        publisher.publish(runId, new ModelDelta(1, "webflux"));
        FluxExchangeResult<String> response = pendingResponse.get(5, TimeUnit.SECONDS);

        StepVerifier.create(response.getResponseBody())
                .assertNext(data -> assertTrue(data.contains("webflux")))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertEquals(0, hub.subscriberCount(runId));
        assertTrue(hub.isOpen(runId));
        hub.closeRun(runId);
    }

    private void awaitSubscriber(ExecutionStreamHub hub, String runId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hub.subscriberCount(runId) == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, hub.subscriberCount(runId));
    }

    @Test
    void mapsSequenceAndKindToSseAndDisconnectOnlyUnsubscribes() {
        String runId = "run-sse";
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(
                hub,
                Clock.fixed(Instant.parse("2026-09-21T13:00:00Z"), ZoneOffset.UTC)
        );
        ExecutionStreamSseController controller = new ExecutionStreamSseController(hub);
        hub.openRun(runId);

        StepVerifier.create(controller.stream(runId, "99"))
                .then(() -> publisher.publish(runId, new ModelDelta(1, "hello")))
                .assertNext(sse -> assertSse(sse, 1L, "hello"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertTrue(hub.isOpen(runId));
        assertEquals(0, hub.subscriberCount(runId));

        ExecutionStreamSubscription later = hub.subscribe(runId);
        StepVerifier.create(later.events())
                .then(() -> publisher.publish(runId, new ModelDelta(1, "still-running")))
                .assertNext(event -> {
                    assertEquals(2L, event.streamSequence());
                    assertEquals("still-running", ((ModelDelta) event.payload()).content());
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertTrue(later.isTerminated());
        later.close();
        hub.closeRun(runId);
    }

    private void assertSse(
            ServerSentEvent<ExecutionStreamEvent> sse,
            long expectedSequence,
            String expectedContent
    ) {
        assertEquals(Long.toString(expectedSequence), sse.id());
        assertEquals("MODEL_DELTA", sse.event());
        assertEquals(expectedSequence, sse.data().streamSequence());
        assertEquals(expectedContent, ((ModelDelta) sse.data().payload()).content());
    }
}
