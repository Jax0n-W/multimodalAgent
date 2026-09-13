package com.multimodalAgent.agent.runtime.extension;

/**
 * A synchronous, invocation-scoped capability for continuing runtime execution.
 * Implementations must not retain or invoke it after the surrounding middleware call returns or
 * throws, and must not transfer it to another thread.
 */
@FunctionalInterface
public interface RuntimeInvocation<T> {

    T proceed();
}
