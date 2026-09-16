package com.multimodalAgent.agent.coordination.watchdog;

import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLeaseWatchdogHardeningTest {

    @Test
    void stopMustWaitForInFlightRenewAndPreventEveryLaterMeaningfulRenew() throws Exception {
        BlockingStore store = new BlockingStore(RunLeaseRenewResult.RENEWED);
        ControlledScheduler scheduler = new ControlledScheduler();
        RunLeaseSession session = session("linear-stop");
        RunLeaseWatchdog watchdog = watchdog(store, scheduler, session);
        watchdog.start();

        CompletableFuture<Void> renewal = CompletableFuture.runAsync(scheduler::tick);
        assertTrue(store.renewEntered.await(1, TimeUnit.SECONDS));
        CompletableFuture<Void> stop = CompletableFuture.runAsync(watchdog::stop);

        assertFalse(stop.isDone(), "stop must linearize after the in-flight renew");
        store.allowRenew.countDown();
        renewal.get(1, TimeUnit.SECONDS);
        stop.get(1, TimeUnit.SECONDS);

        scheduler.tickEvenWhenCancelled();
        assertEquals(1, store.renewCalls);
        assertFalse(watchdog.isRunning());
    }

    @Test
    void everyLateRenewCompletionMustBeHarmlessAfterSessionIsClosed() throws Exception {
        for (RunLeaseRenewResult result : RunLeaseRenewResult.values()) {
            BlockingStore store = new BlockingStore(result);
            ControlledScheduler scheduler = new ControlledScheduler();
            RunLeaseSession session = session("closed-callback-" + result);
            RunLeaseWatchdog watchdog = watchdog(store, scheduler, session);
            watchdog.start();

            CompletableFuture<Void> renewal = CompletableFuture.runAsync(scheduler::tick);
            assertTrue(store.renewEntered.await(1, TimeUnit.SECONDS));
            session.beginClosing();
            session.close();
            store.allowRenew.countDown();

            assertDoesNotThrow(() -> renewal.get(1, TimeUnit.SECONDS));
            assertEquals(RunLeaseState.CLOSED, session.state());
            assertTrue(session.firstFailure().isEmpty());
            scheduler.tickEvenWhenCancelled();
            assertEquals(1, store.renewCalls);
        }
    }

    @Test
    void schedulerShutdownMustFailClosedInsteadOfLeavingSessionActive() throws Exception {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorLeaseRenewalScheduler scheduler =
                new ScheduledExecutorLeaseRenewalScheduler(executor);
        RunLeaseSession session = session("scheduler-shutdown");
        RunLeaseWatchdog watchdog = watchdog(
                new ImmediateStore(RunLeaseRenewResult.RENEWED),
                scheduler,
                session
        );
        watchdog.start();

        AutoCloseable lifecycle = assertInstanceOf(AutoCloseable.class, scheduler);
        lifecycle.close();

        assertEquals(RunLeaseState.LOST, session.state());
        assertEquals(
                RunLeaseFailureKind.COORDINATION_UNAVAILABLE,
                session.firstFailure().orElseThrow()
        );
        assertFalse(watchdog.isRunning());
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentStopMustCancelExactlyOnce() throws Exception {
        ControlledScheduler scheduler = new ControlledScheduler();
        RunLeaseWatchdog watchdog = watchdog(
                new ImmediateStore(RunLeaseRenewResult.RENEWED),
                scheduler,
                session("concurrent-stop")
        );
        watchdog.start();
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?>[] stops = new CompletableFuture<?>[callers];
        var stopExecutor = Executors.newFixedThreadPool(callers);
        for (int index = 0; index < callers; index++) {
            stops[index] = CompletableFuture.runAsync(() -> {
                ready.countDown();
                await(start);
                watchdog.stop();
            }, stopExecutor);
        }

        assertTrue(ready.await(1, TimeUnit.SECONDS));
        start.countDown();
        CompletableFuture.allOf(stops).get(1, TimeUnit.SECONDS);
        stopExecutor.shutdown();

        assertEquals(1, scheduler.cancelCalls.get());
        assertFalse(watchdog.isRunning());
        assertTrue(stopExecutor.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void sharedSchedulerMustKeepHealthyWatchdogsRenewingWhenTwoCallsAreSlow() throws Exception {
        int watchdogCount = 30;
        int slowCount = 2;
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
        ScheduledExecutorLeaseRenewalScheduler scheduler =
                new ScheduledExecutorLeaseRenewalScheduler(executor);
        CountDownLatch slowEntered = new CountDownLatch(slowCount);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch healthyRenewed = new CountDownLatch(watchdogCount - slowCount);
        RunLeaseWatchdog[] watchdogs = new RunLeaseWatchdog[watchdogCount];

        for (int index = 0; index < watchdogCount; index++) {
            RunLeaseStore store = index < slowCount
                    ? new LatchingStore(slowEntered, releaseSlow)
                    : new SignallingStore(healthyRenewed);
            watchdogs[index] = watchdog(store, scheduler, session("stress-" + index));
            watchdogs[index].start();
        }

        assertTrue(slowEntered.await(1, TimeUnit.SECONDS));
        assertTrue(
                healthyRenewed.await(2, TimeUnit.SECONDS),
                "slow renewals must not starve every other active run"
        );
        releaseSlow.countDown();
        for (RunLeaseWatchdog watchdog : watchdogs) {
            watchdog.stop();
        }
        scheduler.close();

        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        for (RunLeaseWatchdog watchdog : watchdogs) {
            assertFalse(watchdog.isRunning());
        }
    }

    private RunLeaseSession session(String suffix) {
        return new RunLeaseSession(new RunLease("run-" + suffix, "token-" + suffix));
    }

    private RunLeaseWatchdog watchdog(
            RunLeaseStore store,
            LeaseRenewalScheduler scheduler,
            RunLeaseSession session
    ) {
        return new RunLeaseWatchdog(store, session, scheduler, Duration.ofMillis(10));
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private Runnable task;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            this.task = task;
            return () -> {
                if (cancelled.compareAndSet(false, true)) {
                    cancelCalls.incrementAndGet();
                }
            };
        }

        private void tick() {
            if (!cancelled.get()) {
                task.run();
            }
        }

        private void tickEvenWhenCancelled() {
            task.run();
        }
    }

    private static final class BlockingStore implements RunLeaseStore {

        private final RunLeaseRenewResult result;
        private final CountDownLatch renewEntered = new CountDownLatch(1);
        private final CountDownLatch allowRenew = new CountDownLatch(1);
        private volatile int renewCalls;

        private BlockingStore(RunLeaseRenewResult result) {
            this.result = result;
        }

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            renewCalls++;
            renewEntered.countDown();
            await(allowRenew);
            return result;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ImmediateStore implements RunLeaseStore {

        private final RunLeaseRenewResult result;

        private ImmediateStore(RunLeaseRenewResult result) {
            this.result = result;
        }

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return result;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class LatchingStore implements RunLeaseStore {

        private final CountDownLatch entered;
        private final CountDownLatch release;

        private LatchingStore(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release slow renewal");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Slow renewal was interrupted", exception);
            }
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SignallingStore implements RunLeaseStore {

        private final CountDownLatch renewed;
        private final AtomicBoolean signalled = new AtomicBoolean();

        private SignallingStore(CountDownLatch renewed) {
            this.renewed = renewed;
        }

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            if (signalled.compareAndSet(false, true)) {
                renewed.countDown();
            }
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            throw new UnsupportedOperationException();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for controlled lease operation");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for controlled lease operation", exception);
        }
    }
}
