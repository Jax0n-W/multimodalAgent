package com.multimodalAgent.agent.recovery;

/** Observes external state only; implementations must never invoke the original tool operation. */
public interface ToolReconciler {

    String strategyId();

    ToolReconciliationResult reconcile(ToolReconciliationContext context);
}
