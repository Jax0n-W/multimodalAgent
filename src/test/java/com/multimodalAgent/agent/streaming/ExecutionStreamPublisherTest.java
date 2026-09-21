package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.RunStartedEvent;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEventKind;
import com.multimodalAgent.agent.stream.ExecutionStreamPayload;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionStreamPublisherTest {

    private static final String RUN_ID = "run-stream";
    private static final Instant PUBLICATION_TIME = Instant.parse("2026-09-21T10:15:30Z");

    @Test
    void concurrentProducersShareOneExactlyOrderedSequenceSpace() throws Exception {
        int threadCount = 32;
        int eventCount = 1_024;
        ExecutionStreamHub hub = new ExecutionStreamHub(eventCount + 32);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription subscription = hub.subscribe(RUN_ID);
        List<ExecutionStreamEvent> observed = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch received = new CountDownLatch(eventCount);
        Disposable disposable = subscription.events().subscribe(
                event -> {
                    observed.add(event);
                    received.countDown();
                },
                failure::set
        );
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier start = new CyclicBarrier(threadCount);
        AtomicInteger payloadIndex = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(eventCount);
        try {
            for (int index = 0; index < eventCount; index++) {
                executor.submit(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        int current = payloadIndex.getAndIncrement();
                        publisher.publish(RUN_ID, payload(current));
                    } catch (Exception exception) {
                        failure.compareAndSet(null, exception);
                    } finally {
                        completed.countDown();
                    }
                });
            }

            assertTrue(completed.await(20, TimeUnit.SECONDS));
            assertTrue(received.await(20, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(eventCount, observed.size());
            for (int index = 0; index < eventCount; index++) {
                assertEquals(index + 1L, observed.get(index).streamSequence());
            }
            assertTrue(observed.stream().anyMatch(
                    event -> event.kind() == ExecutionStreamEventKind.RUNTIME_EVENT
            ));
            assertTrue(observed.stream().anyMatch(
                    event -> event.kind() == ExecutionStreamEventKind.MODEL_DELTA
            ));
            assertTrue(observed.stream().anyMatch(
                    event -> event.kind() == ExecutionStreamEventKind.CONTROL_EVENT
            ));
        } finally {
            disposable.dispose();
            subscription.close();
            hub.closeRun(RUN_ID);
            executor.shutdownNow();
        }
    }

    @Test
    void multipleSubscribersObserveTheSameOrderedEvents() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub(64);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription first = hub.subscribe(RUN_ID);
        ExecutionStreamSubscription second = hub.subscribe(RUN_ID);
        BlockingQueue<ExecutionStreamEvent> firstEvents = new LinkedBlockingQueue<>();
        BlockingQueue<ExecutionStreamEvent> secondEvents = new LinkedBlockingQueue<>();
        Disposable firstDisposable = first.events().subscribe(firstEvents::offer);
        Disposable secondDisposable = second.events().subscribe(secondEvents::offer);
        try {
            for (int index = 0; index < 20; index++) {
                publisher.publish(RUN_ID, new ModelDelta(1, "chunk-" + index));
            }

            List<ExecutionStreamEvent> firstObserved = take(firstEvents, 20);
            List<ExecutionStreamEvent> secondObserved = take(secondEvents, 20);
            assertEquals(firstObserved, secondObserved);
            for (int index = 0; index < 20; index++) {
                assertEquals(index + 1L, firstObserved.get(index).streamSequence());
            }
        } finally {
            firstDisposable.dispose();
            secondDisposable.dispose();
            first.close();
            second.close();
            hub.closeRun(RUN_ID);
        }
    }

    @Test
    void slowSubscriberOverflowDisconnectsOnlyThatSubscriber() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub(4);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription slow = hub.subscribe(RUN_ID);
        ExecutionStreamSubscription healthy = hub.subscribe(RUN_ID);
        BlockingQueue<ExecutionStreamEvent> healthyEvents = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> healthyFailure = new AtomicReference<>();
        Disposable healthyDisposable = healthy.events().subscribe(
                healthyEvents::offer,
                healthyFailure::set
        );
        try {
            for (int index = 1; index <= 12; index++) {
                publisher.publish(RUN_ID, new ModelDelta(1, "chunk-" + index));
                ExecutionStreamEvent event = healthyEvents.poll(5, TimeUnit.SECONDS);
                assertNotNull(event);
                assertEquals(index, event.streamSequence());
            }

            assertTrue(slow.isTerminated());
            assertFalse(healthy.isTerminated());
            assertEquals(1, hub.subscriberCount(RUN_ID));
            assertNull(healthyFailure.get());
        } finally {
            healthyDisposable.dispose();
            slow.close();
            healthy.close();
            hub.closeRun(RUN_ID);
        }
    }

    @Test
    void brokenSubscriberCallbackCannotReachPublisherOrHealthySubscriber() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub(16);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription broken = hub.subscribe(RUN_ID);
        ExecutionStreamSubscription healthy = hub.subscribe(RUN_ID);
        BlockingQueue<ExecutionStreamEvent> healthyEvents = new LinkedBlockingQueue<>();
        CountDownLatch brokenTerminated = new CountDownLatch(1);
        Disposable brokenDisposable = broken.events()
                .doFinally(ignored -> brokenTerminated.countDown())
                .subscribe(
                        ignored -> {
                            throw new IllegalStateException("transport callback failed");
                        },
                        ignored -> {
                        }
                );
        Disposable healthyDisposable = healthy.events().subscribe(healthyEvents::offer);
        try {
            publisher.publish(RUN_ID, new ModelDelta(1, "first"));
            assertTrue(brokenTerminated.await(5, TimeUnit.SECONDS));
            assertEquals(1L, healthyEvents.poll(5, TimeUnit.SECONDS).streamSequence());

            publisher.publish(RUN_ID, new ModelDelta(1, "second"));
            assertEquals(2L, healthyEvents.poll(5, TimeUnit.SECONDS).streamSequence());
            assertEquals(1, hub.subscriberCount(RUN_ID));
        } finally {
            brokenDisposable.dispose();
            healthyDisposable.dispose();
            broken.close();
            healthy.close();
            hub.closeRun(RUN_ID);
        }
    }

    @Test
    void lateSubscriberReceivesFutureEventsOnly() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub(16);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        for (int index = 1; index <= 5; index++) {
            publisher.publish(RUN_ID, new ModelDelta(1, "before-" + index));
        }
        ExecutionStreamSubscription subscription = hub.subscribe(RUN_ID);
        BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
        Disposable disposable = subscription.events().subscribe(events::offer);
        try {
            for (int index = 6; index <= 10; index++) {
                publisher.publish(RUN_ID, new ModelDelta(1, "after-" + index));
            }

            List<ExecutionStreamEvent> observed = take(events, 5);
            assertEquals(List.of(6L, 7L, 8L, 9L, 10L), observed.stream()
                    .map(ExecutionStreamEvent::streamSequence)
                    .toList());
        } finally {
            disposable.dispose();
            subscription.close();
            hub.closeRun(RUN_ID);
        }
    }

    @Test
    void runtimeTimestampAndSequenceRemainSeparateFromLiveEnvelope() {
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription subscription = hub.subscribe(RUN_ID);
        Instant runtimeTime = Instant.parse("2026-09-20T01:02:03Z");
        RunStartedEvent runtimeEvent = runtimeEvent(41, runtimeTime);

        StepVerifier.create(subscription.events())
                .then(() -> publisher.publish(RUN_ID, new RuntimeEventPayload(runtimeEvent)))
                .assertNext(event -> {
                    assertEquals(1L, event.streamSequence());
                    assertEquals(PUBLICATION_TIME, event.occurredAt());
                    RuntimeEventPayload payload = (RuntimeEventPayload) event.payload();
                    assertEquals(runtimeEvent, payload.event());
                    assertEquals(41L, payload.event().sequence());
                    assertEquals(runtimeTime, payload.event().occurredAt());
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        hub.closeRun(RUN_ID);
    }

    @Test
    void explicitCloseCompletesSubscribersAndCleansState() {
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutionStreamPublisher publisher = publisher(hub);
        hub.openRun(RUN_ID);
        ExecutionStreamSubscription first = hub.subscribe(RUN_ID);

        StepVerifier.create(first.events())
                .then(() -> publisher.publish(RUN_ID, new ModelDelta(1, "before-close")))
                .expectNextMatches(event -> event.streamSequence() == 1L)
                .then(() -> hub.closeRun(RUN_ID))
                .verifyComplete();

        assertFalse(hub.isOpen(RUN_ID));
        assertEquals(0, hub.subscriberCount(RUN_ID));
        assertThrows(IllegalStateException.class, () -> hub.subscribe(RUN_ID));
    }

    @Test
    void concurrentOpenAllowsOnlyOneLiveAuthority() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub(8);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(2);
        try {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        hub.openRun(RUN_ID);
                        opened.incrementAndGet();
                    } catch (IllegalStateException duplicate) {
                        rejected.incrementAndGet();
                    } catch (Exception unexpected) {
                        throw new AssertionError(unexpected);
                    } finally {
                        completed.countDown();
                    }
                });
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, opened.get());
            assertEquals(1, rejected.get());
            assertTrue(hub.isOpen(RUN_ID));
        } finally {
            hub.closeRun(RUN_ID);
            executor.shutdownNow();
        }
    }

    private ExecutionStreamPublisher publisher(ExecutionStreamHub hub) {
        return new ExecutionStreamPublisher(
                hub,
                Clock.fixed(PUBLICATION_TIME, ZoneOffset.UTC)
        );
    }

    private ExecutionStreamPayload payload(int index) {
        return switch (index % 3) {
            case 0 -> new RuntimeEventPayload(runtimeEvent(index + 1L, PUBLICATION_TIME));
            case 1 -> new ModelDelta(1, "delta-" + index);
            default -> new ControlEvent(ExecutionControlState.RUNNING);
        };
    }

    private RunStartedEvent runtimeEvent(long sequence, Instant occurredAt) {
        return new RunStartedEvent(new AgentEventMetadata(
                UUID.randomUUID().toString(),
                RUN_ID,
                sequence,
                occurredAt,
                0
        ));
    }

    private List<ExecutionStreamEvent> take(
            BlockingQueue<ExecutionStreamEvent> events,
            int count
    ) throws InterruptedException {
        List<ExecutionStreamEvent> observed = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ExecutionStreamEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            observed.add(event);
        }
        return observed;
    }
}
