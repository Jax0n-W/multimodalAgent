package com.multimodalAgent.agent.context;

/** Read-only, framework-neutral provider of model-visible semantic context. */
public interface ContextSource {

    String sourceId();

    String sourceVersion();

    int order();

    ContextContribution load(ContextAssemblyInput input);
}
