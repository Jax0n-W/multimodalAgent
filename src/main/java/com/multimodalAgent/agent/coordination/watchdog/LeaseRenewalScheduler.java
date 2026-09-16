package com.multimodalAgent.agent.coordination.watchdog;

import java.time.Duration;

/** Shared scheduling port used by per-execution lease watchdogs. */
public interface LeaseRenewalScheduler {

    ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval);

    /**
     * Schedules renewal and supplies a fail-closed callback for scheduler lifecycle loss.
     * Implementations that cannot observe their own lifecycle retain the original scheduling
     * contract; production schedulers should override this method.
     */
    default ScheduledRenewal scheduleWithFixedDelay(
            Runnable task,
            Runnable onSchedulerUnavailable,
            Duration interval
    ) {
        return scheduleWithFixedDelay(task, interval);
    }

    @FunctionalInterface
    interface ScheduledRenewal {

        void cancel();
    }
}
