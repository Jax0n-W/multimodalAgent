package com.multimodalAgent.agent.runtime.extension;

/**
 * Propagates cancellation state through a run without defining cancellation behavior.
 * Phase 5 does not interrupt threads, models, or tools and does not emit cancellation events.
 */
@FunctionalInterface
public interface CancellationContext {

    CancellationContext NONE = () -> false;

    boolean isCancellationRequested();
}
