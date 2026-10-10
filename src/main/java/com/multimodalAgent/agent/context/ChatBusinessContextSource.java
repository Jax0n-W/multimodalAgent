package com.multimodalAgent.agent.context;

/** Adds immutable, trusted chat-business instructions supplied for the current run only. */
public final class ChatBusinessContextSource implements ContextSource {

    public static final String SOURCE_ID = "chat-business-context";
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
        return new ContextContribution(input.trustedBusinessContext());
    }
}
