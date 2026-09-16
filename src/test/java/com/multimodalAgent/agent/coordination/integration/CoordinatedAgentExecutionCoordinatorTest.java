package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.RunAlreadyActiveException;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoordinatedAgentExecutionCoordinatorTest {

    @Test
    void acquireSuccessMustExecuteDelegateAndReleaseExactlyOnce() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        AtomicInteger delegateCalls = new AtomicInteger();
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> {
                    delegateCalls.incrementAndGet();
                    return expected;
                }
        );

        AgentRunResult actual = coordinator.execute(request("run-1", "session-1", "request-1"));

        assertSame(expected, actual);
        assertEquals(1, delegateCalls.get());
        assertEquals(1, store.acquireCalls);
        assertEquals(1, store.releaseCalls);
        assertEquals(store.acquiredLease, store.releasedLeases.get(0));
        assertEquals(0, store.renewCalls);
    }

    @Test
    void alreadyActiveMustRejectBeforePersistentExecution() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.acquireMode = AcquireMode.ALREADY_ACTIVE;
        AtomicInteger delegateCalls = new AtomicInteger();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> {
                    delegateCalls.incrementAndGet();
                    return completedResult();
                }
        );

        RunAlreadyActiveException exception = assertThrows(
                RunAlreadyActiveException.class,
                () -> coordinator.execute(request("run-1", "session-1", "request-1"))
        );

        assertEquals("run-1", exception.runId());
        assertEquals(0, delegateCalls.get());
        assertEquals(1, store.acquireCalls);
        assertEquals(0, store.releaseCalls);
    }

    @Test
    void unavailableAcquireMustFailClosedBeforePersistentExecution() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.acquireMode = AcquireMode.UNAVAILABLE;
        AtomicInteger delegateCalls = new AtomicInteger();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> {
                    delegateCalls.incrementAndGet();
                    return completedResult();
                }
        );

        CoordinationUnavailableException exception = assertThrows(
                CoordinationUnavailableException.class,
                () -> coordinator.execute(request("run-1", "session-1", "request-1"))
        );

        assertEquals("run-1", exception.runId());
        assertEquals(0, delegateCalls.get());
        assertEquals(1, store.acquireCalls);
        assertEquals(0, store.releaseCalls);
    }

    @Test
    void normalCompletionMustReturnTheExactCoreResultInstance() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        AgentRunResult expected = completedResult();

        AgentRunResult actual = coordinator(store, request -> expected).execute(
                request("run-1", "session-1", "request-1")
        );

        assertSame(expected, actual);
        assertEquals(RunLeaseReleaseResult.RELEASED, store.releaseResult);
    }

    @Test
    void admissionFailureMustStillReleaseAndPreserveTheOriginalException() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        ExecutionPersistenceException admissionFailure = new ExecutionPersistenceException(
                "admission failed"
        );
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> {
                    throw admissionFailure;
                }
        );

        ExecutionPersistenceException actual = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator.execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(admissionFailure, actual);
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void runtimeHarnessFailureMustStillReleaseAndRemainPrimary() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        IllegalStateException runtimeFailure = new IllegalStateException("runtime failed");
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> {
                    throw runtimeFailure;
                }
        );

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(runtimeFailure, actual);
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void persistenceFailureMustRemainUnchangedWhenReleaseSucceeds() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        ExecutionPersistenceException persistenceFailure = new ExecutionPersistenceException(
                "persistence failed"
        );

        ExecutionPersistenceException actual = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator(store, request -> {
                    throw persistenceFailure;
                }).execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(persistenceFailure, actual);
        assertEquals(0, actual.getSuppressed().length);
    }

    @Test
    void delegateFailureMustRemainPrimaryWhenReleaseIsUnavailable() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.releaseResult = RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
        IllegalStateException delegateFailure = new IllegalStateException("delegate failed");

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator(store, request -> {
                    throw delegateFailure;
                }).execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(delegateFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(CoordinationUnavailableException.class, actual.getSuppressed()[0]);
    }

    @Test
    void persistenceFailureMustWinOverReleaseCoordinationFailure() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.releaseResult = RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
        ExecutionPersistenceException persistenceFailure = new ExecutionPersistenceException(
                "persistence failed"
        );

        ExecutionPersistenceException actual = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator(store, request -> {
                    throw persistenceFailure;
                }).execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(persistenceFailure, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(CoordinationUnavailableException.class, actual.getSuppressed()[0]);
    }

    @Test
    void successfulCoreResultMustSurviveUnavailableReleaseWithLostDiagnostic() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.releaseResult = RunLeaseReleaseResult.COORDINATION_UNAVAILABLE;
        List<ObservedCleanup> diagnostics = new ArrayList<>();
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> expected,
                (state, failureKind) -> diagnostics.add(new ObservedCleanup(state, failureKind))
        );

        AgentRunResult actual = coordinator.execute(request("run-1", "session-1", "request-1"));

        assertSame(expected, actual);
        assertEquals(AgentStopReason.COMPLETED, actual.stopReason());
        assertEquals(
                List.of(new ObservedCleanup(
                        RunLeaseState.LOST,
                        RunLeaseFailureKind.COORDINATION_UNAVAILABLE
                )),
                diagnostics
        );
    }

    @Test
    void successfulCoreResultMustSurviveExplicitOwnershipLossBeforeClose() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.releaseResult = RunLeaseReleaseResult.NO_LONGER_OWNER;
        List<ObservedCleanup> diagnostics = new ArrayList<>();
        AgentRunResult expected = completedResult();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> expected,
                (state, failureKind) -> diagnostics.add(new ObservedCleanup(state, failureKind))
        );

        AgentRunResult actual = coordinator.execute(request("run-1", "session-1", "request-1"));

        assertSame(expected, actual);
        assertEquals(
                List.of(new ObservedCleanup(
                        RunLeaseState.LOST,
                        RunLeaseFailureKind.EXPLICIT_LEASE_LOSS
                )),
                diagnostics
        );
        assertEquals(1, store.releaseCalls);
    }

    @Test
    void failureBeforeRuntimeStartsMustStillReleaseAnAcquiredLease() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();

        assertThrows(ExecutionPersistenceException.class, () ->
                coordinator(store, request -> {
                    throw new ExecutionPersistenceException("failed before runtime");
                }).execute(request("run-1", "session-1", "request-1"))
        );

        assertEquals(1, store.releaseCalls);
    }

    @Test
    void acquisitionMustUseRunIdRatherThanRequestIdOrSessionId() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();

        coordinator(store, request -> completedResult()).execute(
                request("expected-run", "different-session", "different-request")
        );

        assertEquals(List.of("expected-run"), store.acquiredRunIds);
    }

    @Test
    void sameSessionWithDifferentRunIdsMustBeCoordinatedIndependently() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        CoordinatedAgentExecutionCoordinator coordinator = coordinator(
                store,
                request -> completedResult()
        );

        coordinator.execute(request("run-a", "shared-session", "request-a"));
        coordinator.execute(request("run-b", "shared-session", "request-b"));

        assertEquals(List.of("run-a", "run-b"), store.acquiredRunIds);
        assertEquals(2, store.releaseCalls);
    }

    @Test
    void contentionMustBeRejectedWithoutWaitingOrRetrying() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.acquireMode = AcquireMode.ALREADY_ACTIVE;

        assertThrows(RunAlreadyActiveException.class, () ->
                coordinator(store, request -> completedResult()).execute(
                        request("run-1", "session-1", "request-1")
                )
        );

        assertEquals(1, store.acquireCalls);
        assertEquals(0, store.releaseCalls);
    }

    @Test
    void thrownReleaseFailureMustBeMappedAndSuppressedBehindDelegateFailure() {
        FakeRunLeaseStore store = new FakeRunLeaseStore();
        store.releaseFailure = new IllegalStateException("unexpected adapter failure");
        IllegalArgumentException delegateFailure = new IllegalArgumentException("delegate failed");

        IllegalArgumentException actual = assertThrows(
                IllegalArgumentException.class,
                () -> coordinator(store, request -> {
                    throw delegateFailure;
                }).execute(request("run-1", "session-1", "request-1"))
        );

        assertSame(delegateFailure, actual);
        CoordinationUnavailableException suppressed = assertInstanceOf(
                CoordinationUnavailableException.class,
                actual.getSuppressed()[0]
        );
        assertSame(store.releaseFailure, suppressed.getCause());
    }

    private CoordinatedAgentExecutionCoordinator coordinator(
            FakeRunLeaseStore store,
            java.util.function.Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        return coordinator(store, delegate, (state, failureKind) -> {
        });
    }

    private CoordinatedAgentExecutionCoordinator coordinator(
            FakeRunLeaseStore store,
            java.util.function.Function<AgentExecutionRequest, AgentRunResult> delegate,
            java.util.function.BiConsumer<RunLeaseState, RunLeaseFailureKind> observer
    ) {
        return new CoordinatedAgentExecutionCoordinator(
                store,
                session -> new com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog(
                        store,
                        session,
                        (task, interval) -> () -> {
                        },
                        java.time.Duration.ofSeconds(20)
                ),
                delegate,
                observer
        );
    }

    private AgentExecutionRequest request(String runId, String sessionId, String requestId) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        sessionId,
                        List.of(AgentMessage.user("execute")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                requestId,
                7001L
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

    private enum AcquireMode {
        ACQUIRED,
        ALREADY_ACTIVE,
        UNAVAILABLE
    }

    private record ObservedCleanup(
            RunLeaseState state,
            RunLeaseFailureKind failureKind
    ) {
    }

    private static final class FakeRunLeaseStore implements RunLeaseStore {

        private AcquireMode acquireMode = AcquireMode.ACQUIRED;
        private RunLeaseReleaseResult releaseResult = RunLeaseReleaseResult.RELEASED;
        private RuntimeException releaseFailure;
        private final List<String> acquiredRunIds = new ArrayList<>();
        private final List<RunLease> releasedLeases = new ArrayList<>();
        private RunLease acquiredLease;
        private int acquireCalls;
        private int renewCalls;
        private int releaseCalls;

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            acquireCalls++;
            acquiredRunIds.add(runId);
            return switch (acquireMode) {
                case ACQUIRED -> {
                    acquiredLease = new RunLease(runId, "token-" + acquireCalls);
                    yield new RunLeaseAcquireResult.Acquired(acquiredLease);
                }
                case ALREADY_ACTIVE -> new RunLeaseAcquireResult.AlreadyActive(runId);
                case UNAVAILABLE -> new RunLeaseAcquireResult.Unavailable(runId);
            };
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            renewCalls++;
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releaseCalls++;
            releasedLeases.add(lease);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
            return releaseResult;
        }
    }
}
