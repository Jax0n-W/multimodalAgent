package com.multimodalAgent.agent.coordination.watchdog;

import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseStore;

import java.time.Duration;
import java.util.Objects;

public final class DefaultRunLeaseWatchdogFactory implements RunLeaseWatchdogFactory {

    private final RunLeaseStore leaseStore;
    private final LeaseRenewalScheduler scheduler;
    private final Duration renewInterval;

    public DefaultRunLeaseWatchdogFactory(
            RunLeaseStore leaseStore,
            LeaseRenewalScheduler scheduler,
            Duration renewInterval
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.renewInterval = requirePositive(renewInterval);
    }

    @Override
    public RunLeaseWatchdog create(RunLeaseSession session) {
        return new RunLeaseWatchdog(leaseStore, session, scheduler, renewInterval);
    }

    private Duration requirePositive(Duration value) {
        Objects.requireNonNull(value, "renewInterval must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("renewInterval must be positive");
        }
        return value;
    }
}
