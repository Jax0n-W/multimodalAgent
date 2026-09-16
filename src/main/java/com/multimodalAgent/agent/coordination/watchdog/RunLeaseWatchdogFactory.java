package com.multimodalAgent.agent.coordination.watchdog;

import com.multimodalAgent.agent.coordination.RunLeaseSession;

/** Creates one execution-scoped watchdog while sharing its scheduler and lease store. */
@FunctionalInterface
public interface RunLeaseWatchdogFactory {

    RunLeaseWatchdog create(RunLeaseSession session);
}
