package com.multimodalAgent.agent.coordination.watchdog;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
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
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLeaseWatchdogTest {

    @Test
    void startSchedulesRenewalForActiveSession() {
        Fixture fixture = fixture();

        fixture.watchdog.start();

        assertTrue(fixture.watchdog.isRunning());
        assertEquals(1, fixture.scheduler.scheduleCalls);
        assertEquals(Duration.ofSeconds(2), fixture.scheduler.interval);
    }

    @Test
    void scheduledTicksPeriodicallyRenewTheOwnedLease() {
        Fixture fixture = fixture();
        fixture.watchdog.start();

        fixture.scheduler.tick();
        fixture.scheduler.tick();

        assertEquals(2, fixture.store.renewCalls);
        assertEquals(RunLeaseState.ACTIVE, fixture.session.state());
    }

    @Test
    void renewedKeepsSessionActive() {
        Fixture fixture = fixture();
        fixture.store.results.add(RunLeaseRenewResult.RENEWED);
        fixture.watchdog.start();

        fixture.scheduler.tick();

        assertEquals(RunLeaseState.ACTIVE, fixture.session.state());
        assertTrue(fixture.watchdog.isRunning());
    }

    @Test
    void explicitLeaseLossPermanentlyMarksSessionLost() {
        Fixture fixture = fixture();
        fixture.store.results.add(RunLeaseRenewResult.EXPLICIT_LEASE_LOSS);
        fixture.watchdog.start();

        fixture.scheduler.tick();

        assertEquals(RunLeaseState.LOST, fixture.session.state());
        assertEquals(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS,
                fixture.session.firstFailure().orElseThrow());
        assertFalse(fixture.watchdog.isRunning());
        assertEquals(1, fixture.scheduler.cancelCalls);
    }

    @Test
    void unavailableRenewalPermanentlyMarksSessionLost() {
        Fixture fixture = fixture();
        fixture.store.results.add(RunLeaseRenewResult.COORDINATION_UNAVAILABLE);
        fixture.watchdog.start();

        fixture.scheduler.tick();

        assertEquals(RunLeaseState.LOST, fixture.session.state());
        assertEquals(RunLeaseFailureKind.COORDINATION_UNAVAILABLE,
                fixture.session.firstFailure().orElseThrow());
    }

    @Test
    void unexpectedRenewExceptionFailsClosed() {
        Fixture fixture = fixture();
        fixture.store.renewFailure = new IllegalStateException("adapter failed");
        fixture.watchdog.start();

        fixture.scheduler.tick();

        assertEquals(RunLeaseState.LOST, fixture.session.state());
        assertEquals(RunLeaseFailureKind.COORDINATION_UNAVAILABLE,
                fixture.session.firstFailure().orElseThrow());
        assertFalse(fixture.watchdog.isRunning());
    }

    @Test
    void renewedResultCannotReviveLostSession() {
        Fixture fixture = fixture();
        fixture.store.results.add(RunLeaseRenewResult.COORDINATION_UNAVAILABLE);
        fixture.store.results.add(RunLeaseRenewResult.RENEWED);
        fixture.watchdog.start();
        fixture.scheduler.tick();

        fixture.scheduler.tickEvenWhenCancelled();

        assertEquals(1, fixture.store.renewCalls);
        assertEquals(RunLeaseState.LOST, fixture.session.state());
        assertEquals(RunLeaseFailureKind.COORDINATION_UNAVAILABLE,
                fixture.session.firstFailure().orElseThrow());
    }

    @Test
    void stopPreventsFutureRenewal() {
        Fixture fixture = fixture();
        fixture.watchdog.start();

        fixture.watchdog.stop();
        fixture.scheduler.tickEvenWhenCancelled();

        assertEquals(0, fixture.store.renewCalls);
        assertFalse(fixture.watchdog.isRunning());
    }

    @Test
    void stopIsIdempotentAndCancelsScheduledTaskOnce() {
        Fixture fixture = fixture();
        fixture.watchdog.start();

        fixture.watchdog.stop();
        fixture.watchdog.stop();

        assertEquals(1, fixture.scheduler.cancelCalls);
    }

    @Test
    void schedulerStartFailureFailsClosedBeforeExecution() {
        Fixture fixture = fixture();
        fixture.scheduler.scheduleFailure = new IllegalStateException("scheduler rejected task");

        assertThrows(CoordinationUnavailableException.class, fixture.watchdog::start);

        assertEquals(RunLeaseState.LOST, fixture.session.state());
        assertEquals(RunLeaseFailureKind.COORDINATION_UNAVAILABLE,
                fixture.session.firstFailure().orElseThrow());
    }

    @Test
    void watchdogCannotBeStartedTwice() {
        Fixture fixture = fixture();
        fixture.watchdog.start();

        assertThrows(IllegalStateException.class, fixture.watchdog::start);

        fixture.watchdog.stop();
    }

    private Fixture fixture() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        ControlledScheduler scheduler = new ControlledScheduler();
        RunLeaseSession session = new RunLeaseSession(new RunLease("run-1", "token-1"));
        RunLeaseWatchdog watchdog = new RunLeaseWatchdog(
                store,
                session,
                scheduler,
                Duration.ofSeconds(2)
        );
        return new Fixture(store, scheduler, session, watchdog);
    }

    private record Fixture(
            FakeRunLeaseStore store,
            ControlledScheduler scheduler,
            RunLeaseSession session,
            RunLeaseWatchdog watchdog
    ) {
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private Runnable task;
        private Duration interval;
        private RuntimeException scheduleFailure;
        private int scheduleCalls;
        private int cancelCalls;
        private boolean cancelled;

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            scheduleCalls++;
            if (scheduleFailure != null) {
                throw scheduleFailure;
            }
            this.task = task;
            this.interval = interval;
            return () -> {
                if (!cancelled) {
                    cancelled = true;
                    cancelCalls++;
                }
            };
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

    private static final class FakeRunLeaseStore implements RunLeaseStore {

        private final Deque<RunLeaseRenewResult> results = new ArrayDeque<>();
        private RuntimeException renewFailure;
        private int renewCalls;

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            renewCalls++;
            if (renewFailure != null) {
                throw renewFailure;
            }
            return results.isEmpty() ? RunLeaseRenewResult.RENEWED : results.removeFirst();
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            throw new UnsupportedOperationException();
        }
    }
}
