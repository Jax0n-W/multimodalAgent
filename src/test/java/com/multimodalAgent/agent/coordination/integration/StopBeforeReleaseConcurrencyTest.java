package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopBeforeReleaseConcurrencyTest {

    @Test
    void releaseMustHappenOnlyAfterTheInFlightRenewHasCompletedAndStopReturned() throws Exception {
        BlockingStore store = new BlockingStore();
        ControlledScheduler scheduler = new ControlledScheduler();
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        store,
                        new DefaultRunLeaseWatchdogFactory(
                                store,
                                scheduler,
                                Duration.ofMillis(10)
                        ),
                        request -> {
                            CompletableFuture.runAsync(scheduler::tick);
                            await(store.renewEntered);
                            return expected;
                        },
                        (state, failureKind) -> {
                        }
                );

        CompletableFuture<AgentRunResult> execution = CompletableFuture.supplyAsync(() ->
                coordinator.execute(request())
        );
        assertTrue(store.renewEntered.await(1, TimeUnit.SECONDS));
        assertEquals(0, store.releaseCalls.get());
        assertFalse(execution.isDone());

        store.allowRenew.countDown();
        AgentRunResult actual = execution.get(1, TimeUnit.SECONDS);

        assertSame(expected, actual);
        assertTrue(store.renewCompleted.get());
        assertTrue(store.releaseObservedRenewCompletion.get());
        assertEquals(1, store.renewCalls.get());
        assertEquals(1, store.releaseCalls.get());
        scheduler.tickEvenWhenCancelled();
        assertEquals(1, store.renewCalls.get());
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-stop-release",
                        "session-stop-release",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-stop-release",
                11L
        );
    }

    private AgentRunResult completedResult() {
        return new AgentRunResult(
                "done",
                AgentStopReason.COMPLETED,
                1,
                List.of(),
                List.of(AgentMessage.assistant("done")),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private Runnable task;
        private volatile boolean cancelled;

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            this.task = task;
            return () -> cancelled = true;
        }

        private void tick() {
            if (!cancelled) {
                task.run();
            }
        }

        private void tickEvenWhenCancelled() {
            task.run();
        }
    }

    private static final class BlockingStore implements RunLeaseStore {

        private final CountDownLatch renewEntered = new CountDownLatch(1);
        private final CountDownLatch allowRenew = new CountDownLatch(1);
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private final AtomicBoolean renewCompleted = new AtomicBoolean();
        private final AtomicBoolean releaseObservedRenewCompletion = new AtomicBoolean();

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            return new RunLeaseAcquireResult.Acquired(
                    new RunLease(runId, "token-stop-release")
            );
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            renewCalls.incrementAndGet();
            renewEntered.countDown();
            await(allowRenew);
            renewCompleted.set(true);
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releaseCalls.incrementAndGet();
            releaseObservedRenewCompletion.set(renewCompleted.get());
            return RunLeaseReleaseResult.RELEASED;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled coordination step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for controlled coordination step", exception);
        }
    }
}
