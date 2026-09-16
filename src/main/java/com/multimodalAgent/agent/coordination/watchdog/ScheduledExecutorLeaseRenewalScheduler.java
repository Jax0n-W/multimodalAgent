package com.multimodalAgent.agent.coordination.watchdog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapter that multiplexes all run watchdogs over one shared scheduler. */
public final class ScheduledExecutorLeaseRenewalScheduler
        implements LeaseRenewalScheduler, AutoCloseable {

    private final ScheduledExecutorService executor;
    private final Object lifecycleMonitor = new Object();
    private final Set<Registration> registrations = new HashSet<>();
    private boolean closed;

    public ScheduledExecutorLeaseRenewalScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
        return scheduleWithFixedDelay(task, () -> {
        }, interval);
    }

    @Override
    public ScheduledRenewal scheduleWithFixedDelay(
            Runnable task,
            Runnable onSchedulerUnavailable,
            Duration interval
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(
                onSchedulerUnavailable,
                "onSchedulerUnavailable must not be null"
        );
        Objects.requireNonNull(interval, "interval must not be null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        long delayMillis = interval.toMillis();
        if (delayMillis < 1) {
            throw new IllegalArgumentException("interval must be at least one millisecond");
        }
        Registration registration = new Registration(task, onSchedulerUnavailable);
        synchronized (lifecycleMonitor) {
            if (closed || executor.isShutdown()) {
                throw new RejectedExecutionException("Lease renewal scheduler is unavailable");
            }
            registrations.add(registration);
            try {
                ScheduledFuture<?> future = executor.scheduleWithFixedDelay(
                        registration::runTask,
                        delayMillis,
                        delayMillis,
                        TimeUnit.MILLISECONDS
                );
                registration.attach(future);
            } catch (RuntimeException exception) {
                registrations.remove(registration);
                registration.cancelFuture();
                throw exception;
            }
        }
        return () -> cancel(registration);
    }

    /**
     * Stops the owned scheduler and tells every active watchdog that renewal availability ended.
     */
    @Override
    public void close() {
        List<Registration> active;
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            active = new ArrayList<>(registrations);
            registrations.clear();
        }
        executor.shutdown();
        for (Registration registration : active) {
            registration.cancelFuture();
            registration.signalUnavailable();
        }
    }

    private void cancel(Registration registration) {
        synchronized (lifecycleMonitor) {
            registrations.remove(registration);
        }
        registration.cancelFuture();
    }

    private static final class Registration {

        private final Runnable task;
        private final Runnable onSchedulerUnavailable;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean unavailabilitySignalled = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;

        private Registration(Runnable task, Runnable onSchedulerUnavailable) {
            this.task = task;
            this.onSchedulerUnavailable = onSchedulerUnavailable;
        }

        private void attach(ScheduledFuture<?> future) {
            this.future = Objects.requireNonNull(future, "future must not be null");
            if (cancelled.get()) {
                future.cancel(false);
            }
        }

        private void runTask() {
            if (!cancelled.get()) {
                task.run();
            }
        }

        private void cancelFuture() {
            cancelled.set(true);
            ScheduledFuture<?> current = future;
            if (current != null) {
                current.cancel(false);
            }
        }

        private void signalUnavailable() {
            if (unavailabilitySignalled.compareAndSet(false, true)) {
                onSchedulerUnavailable.run();
            }
        }
    }
}
