package com.multimodalAgent.agent.coordination;

import java.util.Objects;
import java.util.Optional;

/**
 * Mutable state for one execution's lease lifecycle.
 *
 * <p>The session is synchronized so a future renewal driver and the execution boundary can safely
 * observe the same monotonic state. It does not own a thread, scheduler, or infrastructure client.</p>
 */
public final class RunLeaseSession {

    private final RunLease lease;
    private RunLeaseState state = RunLeaseState.ACTIVE;
    private RunLeaseFailureKind firstFailure;

    public RunLeaseSession(RunLease lease) {
        this.lease = Objects.requireNonNull(lease, "lease must not be null");
    }

    public RunLease lease() {
        return lease;
    }

    public synchronized RunLeaseState state() {
        return state;
    }

    public synchronized Optional<RunLeaseFailureKind> firstFailure() {
        return Optional.ofNullable(firstFailure);
    }

    public synchronized boolean hasExecutionAuthority() {
        return state == RunLeaseState.ACTIVE;
    }

    public synchronized void markLost(RunLeaseFailureKind failureKind) {
        Objects.requireNonNull(failureKind, "failureKind must not be null");
        if (state == RunLeaseState.ACTIVE) {
            firstFailure = failureKind;
            state = RunLeaseState.LOST;
            return;
        }
        if (state == RunLeaseState.LOST) {
            return;
        }
        throw illegalTransition(state, RunLeaseState.LOST);
    }

    /**
     * Best-effort transition for asynchronous infrastructure callbacks.
     * A callback that arrives after ownership cleanup has begun must not reopen or corrupt the
     * terminal lifecycle, and therefore observes a no-op outside {@link RunLeaseState#ACTIVE}.
     */
    public synchronized boolean markLostIfActive(RunLeaseFailureKind failureKind) {
        Objects.requireNonNull(failureKind, "failureKind must not be null");
        if (state != RunLeaseState.ACTIVE) {
            return false;
        }
        firstFailure = failureKind;
        state = RunLeaseState.LOST;
        return true;
    }

    public synchronized void beginClosing() {
        if (state == RunLeaseState.ACTIVE || state == RunLeaseState.LOST) {
            state = RunLeaseState.CLOSING;
            return;
        }
        throw illegalTransition(state, RunLeaseState.CLOSING);
    }

    public synchronized void close() {
        if (state == RunLeaseState.CLOSING) {
            state = RunLeaseState.CLOSED;
            return;
        }
        throw illegalTransition(state, RunLeaseState.CLOSED);
    }

    public synchronized void assertExecutionAuthority() {
        if (state == RunLeaseState.ACTIVE) {
            return;
        }
        if (state == RunLeaseState.LOST) {
            if (firstFailure == RunLeaseFailureKind.EXPLICIT_LEASE_LOSS) {
                throw new RunLeaseLostException(lease.runId());
            }
            if (firstFailure == RunLeaseFailureKind.COORDINATION_UNAVAILABLE) {
                throw new CoordinationUnavailableException(lease.runId());
            }
        }
        throw new ExecutionCoordinationException(
                lease.runId(),
                "Run lease session does not have execution authority in state " + state
        );
    }

    private IllegalStateException illegalTransition(
            RunLeaseState from,
            RunLeaseState to
    ) {
        return new IllegalStateException("Illegal run lease transition: " + from + " -> " + to);
    }
}
