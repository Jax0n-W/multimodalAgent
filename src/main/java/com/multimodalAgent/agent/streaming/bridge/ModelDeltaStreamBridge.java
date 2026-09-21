package com.multimodalAgent.agent.streaming.bridge;

import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.ModelDeltaObserver;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;

import java.util.Objects;

/** Binds P8.2 model deltas to one run without allocating stream sequence numbers. */
public final class ModelDeltaStreamBridge implements ModelDeltaObserver {

    private final String runId;
    private final ExecutionStreamPublisher streamPublisher;

    public ModelDeltaStreamBridge(String runId, ExecutionStreamPublisher streamPublisher) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        this.runId = runId;
        this.streamPublisher = Objects.requireNonNull(
                streamPublisher,
                "streamPublisher must not be null"
        );
    }

    @Override
    public void onDelta(ModelDelta delta) {
        streamPublisher.publish(runId, Objects.requireNonNull(delta, "delta must not be null"));
    }
}
