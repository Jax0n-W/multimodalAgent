package com.multimodalAgent.agent.runtime.extension;

@FunctionalInterface
public interface CancellationContext {

    CancellationContext NONE = () -> false;

    boolean isCancellationRequested();
}
