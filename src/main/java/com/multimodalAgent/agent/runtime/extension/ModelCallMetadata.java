package com.multimodalAgent.agent.runtime.extension;

public record ModelCallMetadata(int iteration, int messageCount) {

    public ModelCallMetadata {
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
    }
}
