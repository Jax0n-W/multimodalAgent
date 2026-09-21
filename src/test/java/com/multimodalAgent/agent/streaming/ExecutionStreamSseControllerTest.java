package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.controller.ExecutionStreamSseController;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionStreamSseControllerTest {

    @Test
    void liveStreamIsLiveOnlyAndClientCancellationRemovesSubscription() {
        String runId = "run-sse-webflux";
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(
                hub,
                Clock.fixed(Instant.parse("2026-09-21T13:00:00Z"), ZoneOffset.UTC)
        );
        hub.openRun(runId);
        AgentRunRepository runs = ownedRun(runId);
        CurrentUser user = user();
        ExecutionStreamSseController controller = new ExecutionStreamSseController(hub, runs);

        StepVerifier.create(controller.stream(user, runId, "500"))
                .then(() -> {
                    awaitSubscriber(hub, runId);
                    publisher.publish(runId, new ModelDelta(1, "webflux"));
                })
                .assertNext(sse -> assertSse(sse, 1L, "webflux"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertEquals(0, hub.subscriberCount(runId));
        assertTrue(hub.isOpen(runId));
        verify(runs).existsByRunIdAndUserId(runId, user.getId());
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
        AgentRunRepository runs = ownedRun(runId);
        CurrentUser user = user();
        ExecutionStreamSseController controller = new ExecutionStreamSseController(hub, runs);
        hub.openRun(runId);

        StepVerifier.create(controller.stream(user, runId, "99"))
                .then(() -> {
                    awaitSubscriber(hub, runId);
                    publisher.publish(runId, new ModelDelta(1, "hello"));
                })
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

    private AgentRunRepository ownedRun(String runId) {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.existsByRunIdAndUserId(runId, 1L)).thenReturn(true);
        return runs;
    }

    private CurrentUser user() {
        CurrentUser user = mock(CurrentUser.class);
        when(user.getId()).thenReturn(1L);
        return user;
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
