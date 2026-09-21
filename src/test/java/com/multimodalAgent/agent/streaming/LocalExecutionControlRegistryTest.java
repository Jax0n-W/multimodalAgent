package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.controller.ExecutionStreamSseController;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.security.CurrentUser;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
class LocalExecutionControlRegistryTest {

    @Test
    void sseDisconnectDoesNotRequestCancellation() {
        String runId = "run-sse-disconnect";
        ExecutionStreamHub hub = new ExecutionStreamHub();
        hub.openRun(runId);
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(publisher);
        LocalExecutionControlRegistry.Entry entry = registry.create(runId);
        registry.register(entry);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        CurrentUser user = mock(CurrentUser.class);
        when(user.getId()).thenReturn(1L);
        when(runs.existsByRunIdAndUserId(runId, 1L)).thenReturn(true);
        ExecutionStreamSseController sse = new ExecutionStreamSseController(hub, runs);
        try {
            StepVerifier.create(sse.stream(user, runId, null))
                    .then(() -> {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (hub.subscriberCount(runId) == 0 && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                        assertEquals(1, hub.subscriberCount(runId));
                        publisher.publish(runId, new ModelDelta(1, "hello"));
                    })
                    .expectNextCount(1)
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
            assertEquals(0, hub.subscriberCount(runId));
            assertEquals(ExecutionControlState.RUNNING, entry.state());
            assertTrue(entry.trySealNormalCompletion());
            assertEquals(CancelRequestResult.ALREADY_TERMINAL, registry.requestCancel(runId));
        } finally {
            registry.close(entry);
            hub.closeRun(runId);
        }
    }

    @Test
    void concurrentRequestsAcceptExactlyOnceAndPublishOneControlObservation() throws Exception {
        ExecutionStreamHub hub = new ExecutionStreamHub();
        hub.openRun("run-concurrent");
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(publisher);
        LocalExecutionControlRegistry.Entry entry = registry.create("run-concurrent");
        registry.register(entry);
        ExecutionStreamSubscription subscription = hub.subscribe("run-concurrent");
        BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
        Disposable receiver = subscription.events().subscribe(events::offer);
        ExecutorService threads = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CancelRequestResult>> results = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> threads.submit(() -> {
                        ready.countDown();
                        start.await();
                        return registry.requestCancel("run-concurrent");
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long accepted = 0;
            long repeated = 0;
            for (Future<CancelRequestResult> result : results) {
                if (result.get(5, TimeUnit.SECONDS) == CancelRequestResult.ACCEPTED) {
                    accepted++;
                } else {
                    repeated++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(31, repeated);
            ExecutionStreamEvent observation = events.poll(5, TimeUnit.SECONDS);
            assertTrue(observation != null && observation.payload() instanceof ControlEvent);
            assertEquals(1L, observation.streamSequence());
            assertEquals(null, events.poll(200, TimeUnit.MILLISECONDS));
            assertEquals(ExecutionControlState.CANCEL_REQUESTED, entry.state());
        } finally {
            start.countDown();
            threads.shutdownNow();
            registry.close(entry);
            receiver.dispose();
            subscription.close();
            hub.closeRun("run-concurrent");
        }
        assertEquals(0, registry.activeCount());
    }

    @Test
    void terminalAndCancelDecisionsHaveOneLinearizationBoundary() {
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(new ExecutionStreamHub());
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(publisher);
        LocalExecutionControlRegistry.Entry terminalFirst = registry.create("terminal-first");
        registry.register(terminalFirst);
        assertTrue(terminalFirst.trySealNormalCompletion());
        assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                registry.requestCancel("terminal-first"));
        registry.close(terminalFirst);

        LocalExecutionControlRegistry.Entry cancelFirst = registry.create("cancel-first");
        registry.register(cancelFirst);
        assertEquals(CancelRequestResult.ACCEPTED, registry.requestCancel("cancel-first"));
        assertFalse(cancelFirst.trySealNormalCompletion());
        cancelFirst.sealCoreTerminal();
        assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                registry.requestCancel("cancel-first"));
        registry.close(cancelFirst);
        assertEquals(0, registry.activeCount());
        assertEquals(CancelRequestResult.NOT_ACTIVE, registry.requestCancel("missing"));
    }

    @Test
    void unavailableObservationCannotUndoAcceptedCancellation() {
        // No Hub stream is open; publication is unavailable but control intent still sticks.
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(new ExecutionStreamHub());
        LocalExecutionControlRegistry registry = new LocalExecutionControlRegistry(publisher);
        LocalExecutionControlRegistry.Entry entry = registry.create("run-observation");
        registry.register(entry);
        assertEquals(CancelRequestResult.ACCEPTED, registry.requestCancel("run-observation"));
        assertEquals(ExecutionControlState.CANCEL_REQUESTED, entry.state());
        registry.close(entry);
        assertEquals(0, registry.activeCount());
    }
}
