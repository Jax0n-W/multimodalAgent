package com.multimodalAgent.agent.coordination.watchdog;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Adapter that multiplexes all run watchdogs over one shared scheduler. */
public final class ScheduledExecutorLeaseRenewalScheduler implements LeaseRenewalScheduler {

    private final ScheduledExecutorService executor;

    public ScheduledExecutorLeaseRenewalScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        long delayMillis = interval.toMillis();
        if (delayMillis < 1) {
            throw new IllegalArgumentException("interval must be at least one millisecond");
        }
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                task,
                delayMillis,
                delayMillis,
                TimeUnit.MILLISECONDS
        );
        return () -> future.cancel(false);
    }
}
