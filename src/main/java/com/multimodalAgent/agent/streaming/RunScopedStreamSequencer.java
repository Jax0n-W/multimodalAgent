package com.multimodalAgent.agent.streaming;

/**
 * Allocates the single live-observation sequence owned by one open run stream.
 *
 * <p>This type is deliberately package-private: producers publish payloads and never receive or
 * own a sequence allocator.</p>
 */
final class RunScopedStreamSequencer {

    private long current;

    long next() {
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("streamSequence exhausted");
        }
        return ++current;
    }
}
