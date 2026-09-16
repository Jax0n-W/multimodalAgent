package com.multimodalAgent.agent.coordination.watchdog;

import java.time.Duration;

/** Shared scheduling port used by per-execution lease watchdogs. */
public interface LeaseRenewalScheduler {

    ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval);

    @FunctionalInterface
    interface ScheduledRenewal {

        void cancel();
    }
}
