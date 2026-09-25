package com.multimodalAgent.agent.runtime.budget;

import com.multimodalAgent.agent.runtime.extension.RuntimeAttributeKey;

/** Read-only diagnostics published when an AgentRunner invocation leaves the Runtime Core. */
public final class BudgetRuntimeAttributes {

    public static final RuntimeAttributeKey<BudgetUsage> USAGE =
            RuntimeAttributeKey.of("runtime.budget.usage", BudgetUsage.class);

    private BudgetRuntimeAttributes() {
    }
}
