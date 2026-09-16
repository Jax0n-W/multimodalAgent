package com.multimodalAgent.agent.harness;

import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;

/**
 * Adds execution-scoped extension state to the single Runtime context created by the harness.
 *
 * <p>Contributors must not retain the context. They run once, synchronously, before Runtime
 * middleware or Core execution starts.</p>
 */
@FunctionalInterface
public interface AgentRuntimeContextContributor {

    void contribute(AgentRuntimeContext context);
}
