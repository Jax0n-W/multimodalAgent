package com.multimodalAgent.agent.context;

/** P11.1 compatibility source: preserves the caller-provided request messages exactly. */
public final class RequestMessageContextSource implements ContextSource {

    public static final String SOURCE_ID = "request-messages";
    public static final String SOURCE_VERSION = "1";
    public static final int ORDER = 0;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public String sourceVersion() {
        return SOURCE_VERSION;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ContextContribution load(ContextAssemblyInput input) {
        return new ContextContribution(input.requestMessages());
    }
}
