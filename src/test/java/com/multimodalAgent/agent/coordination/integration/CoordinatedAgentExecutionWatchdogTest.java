package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinatedAgentExecutionWatchdogTest {

    @Test
    void lifecycleMustAcquireStartDelegateStopRelease() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        ControlledScheduler scheduler = new ControlledScheduler(order);
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                scheduler,
                request -> {
                    order.add("delegate");
                    assertEquals(1, request.runtimeContextContributors().size());
                    return expected;
                }
        );

        AgentRunResult actual = coordinator.execute(request());

        assertSame(expected, actual);
        assertEquals(
                List.of("acquire", "watchdog-start", "delegate", "watchdog-stop", "release"),
                order
        );
    }

    @Test
    void delegateFailureStillStopsWatchdogReleasesAndRemainsPrimary() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        ControlledScheduler scheduler = new ControlledScheduler(order);
        IllegalStateException failure = new IllegalStateException("delegate failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class, () ->
                coordinator(store, scheduler, request -> {
                    order.add("delegate");
                    throw failure;
                }).execute(request())
        );

        assertSame(failure, actual);
        assertEquals(
                List.of("acquire", "watchdog-start", "delegate", "watchdog-stop", "release"),
                order
        );
    }

    @Test
    void explicitWatchdogLossIsReturnedToCallerAfterDelegateTerminal() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        store.renewResult = RunLeaseRenewResult.EXPLICIT_LEASE_LOSS;
        ControlledScheduler scheduler = new ControlledScheduler(order);

        RunLeaseLostException failure = assertThrows(RunLeaseLostException.class, () ->
                coordinator(store, scheduler, request -> {
                    order.add("delegate");
                    scheduler.tick();
                    order.add("delegate-terminal");
                    return completedResult();
                }).execute(request())
        );

        assertEquals("run-watchdog", failure.runId());
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void unavailableWatchdogRenewalIsReturnedToCallerAfterDelegateTerminal() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        store.renewResult = RunLeaseRenewResult.COORDINATION_UNAVAILABLE;
        ControlledScheduler scheduler = new ControlledScheduler(order);

        CoordinationUnavailableException failure = assertThrows(
                CoordinationUnavailableException.class,
                () -> coordinator(store, scheduler, request -> {
                    scheduler.tick();
                    return completedResult();
                }).execute(request())
        );

        assertEquals("run-watchdog", failure.runId());
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void persistenceFailureRemainsPrimaryWhenLeaseWasAlsoLost() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        store.renewResult = RunLeaseRenewResult.EXPLICIT_LEASE_LOSS;
        ControlledScheduler scheduler = new ControlledScheduler(order);
        ExecutionPersistenceException persistenceFailure =
                new ExecutionPersistenceException("persistence failed");

        ExecutionPersistenceException actual = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator(store, scheduler, request -> {
                    scheduler.tick();
                    throw persistenceFailure;
                }).execute(request())
        );

        assertSame(persistenceFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(RunLeaseLostException.class, actual.getSuppressed()[0]);
    }

    @Test
    void terminalSuccessSurvivesReleaseUnavailable() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        store.releaseResult = RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
        ControlledScheduler scheduler = new ControlledScheduler(order);
        AgentRunResult expected = completedResult();

        AgentRunResult actual = coordinator(store, scheduler, request -> expected).execute(request());

        assertSame(expected, actual);
        assertEquals(AgentStopReason.COMPLETED, actual.stopReason());
    }

    @Test
    void lossDuringWatchdogStartBlocksDelegateAndStillReleases() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        store.renewResult = RunLeaseRenewResult.EXPLICIT_LEASE_LOSS;
        ControlledScheduler scheduler = new ControlledScheduler(order);
        scheduler.runInlineOnSchedule = true;
        AtomicInteger delegateCalls = new AtomicInteger();

        assertThrows(RunLeaseLostException.class, () ->
                coordinator(store, scheduler, request -> {
                    delegateCalls.incrementAndGet();
                    return completedResult();
                }).execute(request())
        );

        assertEquals(0, delegateCalls.get());
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void watchdogSchedulingFailureBlocksDelegateAndStillReleases() {
        List<String> order = new ArrayList<>();
        FakeStore store = new FakeStore(order);
        ControlledScheduler scheduler = new ControlledScheduler(order);
        scheduler.scheduleFailure = new IllegalStateException("scheduler unavailable");
        AtomicInteger delegateCalls = new AtomicInteger();

        CoordinationUnavailableException failure = assertThrows(
                CoordinationUnavailableException.class,
                () -> coordinator(store, scheduler, request -> {
                    delegateCalls.incrementAndGet();
                    return completedResult();
                }).execute(request())
        );

        assertEquals("run-watchdog", failure.runId());
        assertEquals(0, delegateCalls.get());
        assertEquals(1, store.releaseCalls);
    }

    private CoordinatedAgentExecutionCoordinator coordinator(
            FakeStore store,
            ControlledScheduler scheduler,
            java.util.function.Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        return new CoordinatedAgentExecutionCoordinator(
                store,
                new DefaultRunLeaseWatchdogFactory(
                        store,
                        scheduler,
                        Duration.ofSeconds(2)
                ),
                delegate,
                (state, failureKind) -> {
                }
        );
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-watchdog",
                        "session-watchdog",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-watchdog",
                9L
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

        private final List<String> order;
        private Runnable task;
        private boolean cancelled;
        private boolean runInlineOnSchedule;
        private RuntimeException scheduleFailure;

        private ControlledScheduler(List<String> order) {
            this.order = order;
        }

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            order.add("watchdog-start");
            if (scheduleFailure != null) {
                throw scheduleFailure;
            }
            this.task = task;
            if (runInlineOnSchedule) {
                task.run();
            }
            return () -> {
                if (!cancelled) {
                    cancelled = true;
                    order.add("watchdog-stop");
                }
            };
        }

        private void tick() {
            if (!cancelled) {
                task.run();
            }
        }
    }

    private static final class FakeStore implements RunLeaseStore {

        private final List<String> order;
        private RunLeaseRenewResult renewResult = RunLeaseRenewResult.RENEWED;
        private RunLeaseReleaseResult releaseResult = RunLeaseReleaseResult.RELEASED;
        private int releaseCalls;

        private FakeStore(List<String> order) {
            this.order = order;
        }

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            order.add("acquire");
            return new RunLeaseAcquireResult.Acquired(new RunLease(runId, "token-watchdog"));
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return renewResult;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releaseCalls++;
            order.add("release");
            return releaseResult;
        }
    }
}
