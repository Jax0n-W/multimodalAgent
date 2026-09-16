package com.multimodalAgent.agent.coordination.watchdog;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;

import java.time.Duration;
import java.util.Objects;

/**
 * Renews one acquired lease until execution cleanup begins.
 *
 * <p>Renew and stop are serialized on one monitor. Once {@link #stop()} returns, no renewal can
 * still be inside the lease store and later callbacks cannot perform meaningful work.</p>
 */
public final class RunLeaseWatchdog {

    private static final System.Logger LOGGER = System.getLogger(RunLeaseWatchdog.class.getName());

    private final RunLeaseStore leaseStore;
    private final RunLeaseSession session;
    private final LeaseRenewalScheduler scheduler;
    private final Duration renewInterval;
    private Lifecycle lifecycle = Lifecycle.NEW;
    private LeaseRenewalScheduler.ScheduledRenewal scheduledRenewal;

    public RunLeaseWatchdog(
            RunLeaseStore leaseStore,
            RunLeaseSession session,
            LeaseRenewalScheduler scheduler,
            Duration renewInterval
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.renewInterval = requirePositive(renewInterval);
    }

    public synchronized void start() {
        if (lifecycle != Lifecycle.NEW) {
            throw new IllegalStateException("Run lease watchdog can only be started once");
        }
        if (!session.hasExecutionAuthority()) {
            session.assertExecutionAuthority();
        }
        lifecycle = Lifecycle.RUNNING;
        LeaseRenewalScheduler.ScheduledRenewal scheduled;
        try {
            scheduled = Objects.requireNonNull(
                    scheduler.scheduleWithFixedDelay(this::renewSafely, renewInterval),
                    "scheduler returned null"
            );
        } catch (RuntimeException exception) {
            lifecycle = Lifecycle.STOPPED;
            markUnavailable();
            throw new CoordinationUnavailableException(session.lease().runId(), exception);
        }
        scheduledRenewal = scheduled;
        if (lifecycle == Lifecycle.STOPPED) {
            cancelScheduledRenewal(scheduled);
        }
    }

    public void stop() {
        LeaseRenewalScheduler.ScheduledRenewal scheduled;
        synchronized (this) {
            if (lifecycle == Lifecycle.STOPPED) {
                return;
            }
            lifecycle = Lifecycle.STOPPED;
            scheduled = scheduledRenewal;
        }
        if (scheduled != null) {
            cancelScheduledRenewal(scheduled);
        }
    }

    public synchronized boolean isRunning() {
        return lifecycle == Lifecycle.RUNNING;
    }

    private synchronized void renewSafely() {
        if (lifecycle != Lifecycle.RUNNING || !session.hasExecutionAuthority()) {
            lifecycle = Lifecycle.STOPPED;
            return;
        }
        RunLeaseRenewResult result;
        try {
            result = leaseStore.renew(session.lease());
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Unexpected lease renewal failure for " + session.lease().runId()
                            + " (" + exception.getClass().getSimpleName() + ")"
            );
            markUnavailable();
            lifecycle = Lifecycle.STOPPED;
            cancelAfterLoss();
            return;
        }
        if (result == RunLeaseRenewResult.RENEWED) {
            return;
        }
        if (result == RunLeaseRenewResult.EXPLICIT_LEASE_LOSS) {
            session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
        } else {
            markUnavailable();
        }
        lifecycle = Lifecycle.STOPPED;
        cancelAfterLoss();
    }

    private void markUnavailable() {
        if (session.state() == RunLeaseState.ACTIVE) {
            session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
        }
    }

    private void cancelScheduledRenewal(LeaseRenewalScheduler.ScheduledRenewal scheduled) {
        try {
            scheduled.cancel();
        } catch (RuntimeException exception) {
            markUnavailable();
            throw new CoordinationUnavailableException(session.lease().runId(), exception);
        }
    }

    private void cancelAfterLoss() {
        LeaseRenewalScheduler.ScheduledRenewal scheduled = scheduledRenewal;
        scheduledRenewal = null;
        if (scheduled == null) {
            return;
        }
        try {
            scheduled.cancel();
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Could not cancel lost-lease watchdog for " + session.lease().runId()
                            + " (" + exception.getClass().getSimpleName() + ")"
            );
        }
    }

    private Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "renewInterval must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("renewInterval must be positive");
        }
        return value;
    }

    private enum Lifecycle {
        NEW,
        RUNNING,
        STOPPED
    }
}
