package com.multimodalAgent.agent.coordination.integration;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.ExecutionCoordinationException;
import com.multimodalAgent.agent.coordination.RunAlreadyActiveException;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.runtime.AgentRunResult;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Execution-level ownership shell around the existing P6 persistence coordinator.
 *
 * <p>P7.4 composes acquisition, execution-scoped watchdog renewal, Runtime context propagation,
 * and terminal cleanup without changing any Core result.</p>
 */
public final class CoordinatedAgentExecutionCoordinator {

    private static final System.Logger LOGGER = System.getLogger(
            CoordinatedAgentExecutionCoordinator.class.getName()
    );

    private final RunLeaseStore leaseStore;
    private final RunLeaseWatchdogFactory watchdogFactory;
    private final Function<AgentExecutionRequest, AgentRunResult> persistentExecution;
    private final BiConsumer<RunLeaseState, RunLeaseFailureKind> cleanupDiagnosticObserver;

    public CoordinatedAgentExecutionCoordinator(
            RunLeaseStore leaseStore,
            RunLeaseWatchdogFactory watchdogFactory,
            PersistentAgentExecutionCoordinator persistentCoordinator
    ) {
        this(
                leaseStore,
                watchdogFactory,
                Objects.requireNonNull(
                        persistentCoordinator,
                        "persistentCoordinator must not be null"
                )::execute,
                (state, failureKind) -> {
                }
        );
    }

    CoordinatedAgentExecutionCoordinator(
            RunLeaseStore leaseStore,
            RunLeaseWatchdogFactory watchdogFactory,
            Function<AgentExecutionRequest, AgentRunResult> persistentExecution,
            BiConsumer<RunLeaseState, RunLeaseFailureKind> cleanupDiagnosticObserver
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.watchdogFactory = Objects.requireNonNull(
                watchdogFactory,
                "watchdogFactory must not be null"
        );
        this.persistentExecution = Objects.requireNonNull(
                persistentExecution,
                "persistentExecution must not be null"
        );
        this.cleanupDiagnosticObserver = Objects.requireNonNull(
                cleanupDiagnosticObserver,
                "cleanupDiagnosticObserver must not be null"
        );
    }

    public AgentRunResult execute(AgentExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String runId = request.runSpec().runId();
        RunLease lease = acquire(runId);
        RunLeaseSession session = new RunLeaseSession(lease);
        RunLeaseWatchdog watchdog = null;
        Throwable primaryFailure = null;
        try {
            watchdog = startWatchdog(session);
            session.assertExecutionAuthority();
            AgentExecutionRequest coordinatedRequest = request.withRuntimeContextContributor(
                    ExecutionCoordinationBoundaryMiddleware.sessionContributor(session)
            );
            AgentRunResult result = persistentExecution.apply(coordinatedRequest);
            watchdog.stop();
            session.assertExecutionAuthority();
            return result;
        } catch (RuntimeException | Error exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            cleanup(session, watchdog, primaryFailure);
        }
    }

    private RunLeaseWatchdog startWatchdog(RunLeaseSession session) {
        try {
            RunLeaseWatchdog watchdog = Objects.requireNonNull(
                    watchdogFactory.create(session),
                    "watchdogFactory returned null"
            );
            watchdog.start();
            return watchdog;
        } catch (ExecutionCoordinationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (session.state() == RunLeaseState.ACTIVE) {
                session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
            }
            throw new CoordinationUnavailableException(session.lease().runId(), exception);
        }
    }

    private RunLease acquire(String runId) {
        RunLeaseAcquireResult result;
        try {
            result = leaseStore.tryAcquire(runId);
        } catch (RuntimeException exception) {
            throw new CoordinationUnavailableException(runId, exception);
        }
        if (result instanceof RunLeaseAcquireResult.Acquired acquired) {
            RunLease lease = acquired.lease();
            if (!runId.equals(lease.runId())) {
                throw new CoordinationUnavailableException(
                        runId,
                        new IllegalStateException("Lease store returned a different runId")
                );
            }
            return lease;
        }
        if (result instanceof RunLeaseAcquireResult.AlreadyActive) {
            throw new RunAlreadyActiveException(runId);
        }
        if (result instanceof RunLeaseAcquireResult.Unavailable || result == null) {
            throw new CoordinationUnavailableException(runId);
        }
        throw new CoordinationUnavailableException(
                runId,
                new IllegalStateException("Unsupported lease acquisition result")
        );
    }

    private void cleanup(
            RunLeaseSession session,
            RunLeaseWatchdog watchdog,
            Throwable primaryFailure
    ) {
        ExecutionCoordinationException executionLoss = failureForSession(session);
        ExecutionCoordinationException stopFailure = stopWatchdog(session, watchdog);
        ExecutionCoordinationException releaseFailure = release(session);
        ExecutionCoordinationException diagnostic = firstNonNull(
                executionLoss,
                stopFailure,
                releaseFailure
        );
        if (diagnostic != null) {
            recordCleanupDiagnostic(session, diagnostic);
        }
        try {
            session.beginClosing();
            session.close();
        } catch (RuntimeException lifecycleFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(lifecycleFailure);
            } else {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "Could not close run lease session for " + session.lease().runId(),
                        lifecycleFailure
                );
            }
        }
        if (primaryFailure != null) {
            addSuppressedUnlessEquivalent(primaryFailure, executionLoss, FailureOrigin.SESSION);
            addSuppressedUnlessEquivalent(primaryFailure, stopFailure, FailureOrigin.WATCHDOG_STOP);
            if (!sameExplicitOwnershipLoss(executionLoss, releaseFailure)) {
                addSuppressedUnlessEquivalent(
                        primaryFailure,
                        releaseFailure,
                        FailureOrigin.RELEASE
                );
            }
        }
    }

    private ExecutionCoordinationException stopWatchdog(
            RunLeaseSession session,
            RunLeaseWatchdog watchdog
    ) {
        if (watchdog == null) {
            return null;
        }
        try {
            watchdog.stop();
            return null;
        } catch (ExecutionCoordinationException exception) {
            return exception;
        } catch (RuntimeException exception) {
            if (session.state() == RunLeaseState.ACTIVE) {
                session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
            }
            return new CoordinationUnavailableException(session.lease().runId(), exception);
        }
    }

    private ExecutionCoordinationException failureForSession(RunLeaseSession session) {
        return session.firstFailure()
                .map(failureKind -> failureKind == RunLeaseFailureKind.EXPLICIT_LEASE_LOSS
                        ? new RunLeaseLostException(session.lease().runId())
                        : new CoordinationUnavailableException(session.lease().runId()))
                .orElse(null);
    }

    private ExecutionCoordinationException firstNonNull(
            ExecutionCoordinationException first,
            ExecutionCoordinationException second,
            ExecutionCoordinationException third
    ) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private void addSuppressedUnlessEquivalent(
            Throwable primary,
            ExecutionCoordinationException secondary,
            FailureOrigin origin
    ) {
        if (secondary == null || primary == secondary) {
            return;
        }
        if (origin == FailureOrigin.SESSION
                && primary instanceof ExecutionCoordinationException coordinationPrimary
                && coordinationPrimary.getClass().equals(secondary.getClass())
                && coordinationPrimary.runId().equals(secondary.runId())) {
            return;
        }
        for (Throwable existing : primary.getSuppressed()) {
            if (existing == secondary) {
                return;
            }
        }
        primary.addSuppressed(secondary);
    }

    private boolean sameExplicitOwnershipLoss(
            ExecutionCoordinationException executionLoss,
            ExecutionCoordinationException releaseFailure
    ) {
        return executionLoss instanceof RunLeaseLostException
                && releaseFailure instanceof RunLeaseLostException
                && executionLoss.runId().equals(releaseFailure.runId());
    }

    private ExecutionCoordinationException release(RunLeaseSession session) {
        RunLease lease = session.lease();
        RunLeaseReleaseResult result;
        try {
            result = leaseStore.release(lease);
        } catch (RuntimeException exception) {
            session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
            return new CoordinationUnavailableException(lease.runId(), exception);
        }
        if (result == RunLeaseReleaseResult.RELEASED) {
            return null;
        }
        if (result == RunLeaseReleaseResult.NO_LONGER_OWNER) {
            session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
            return new RunLeaseLostException(lease.runId());
        }
        session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
        return new CoordinationUnavailableException(lease.runId());
    }

    private void recordCleanupDiagnostic(
            RunLeaseSession session,
            ExecutionCoordinationException failure
    ) {
        RunLeaseFailureKind failureKind = session.firstFailure().orElseThrow();
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Run lease terminal cleanup failed for " + session.lease().runId()
                        + ": " + failureKind
                        + " (" + failure.getClass().getSimpleName() + ")"
        );
        try {
            cleanupDiagnosticObserver.accept(session.state(), failureKind);
        } catch (RuntimeException observerFailure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Run lease cleanup diagnostic observer failed for "
                            + session.lease().runId()
                            + " (" + observerFailure.getClass().getSimpleName() + ")"
            );
        }
    }

    private enum FailureOrigin {
        SESSION,
        WATCHDOG_STOP,
        RELEASE
    }
}
