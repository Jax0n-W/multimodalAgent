package com.multimodalAgent.agent.runtime.extension;

@FunctionalInterface
public interface RuntimeInvocation<T> {

    T proceed();
}
