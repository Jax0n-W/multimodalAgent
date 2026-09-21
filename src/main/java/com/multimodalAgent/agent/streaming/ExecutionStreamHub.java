package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.stream.ExecutionStreamPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Node-local registry of explicitly opened live run streams. */
@Component
public class ExecutionStreamHub {

    static final int DEFAULT_SUBSCRIBER_BUFFER_CAPACITY = 256;

    private final ConcurrentMap<String, RunStreamState> runs = new ConcurrentHashMap<>();
    private final int subscriberBufferCapacity;

    @Autowired
    public ExecutionStreamHub() {
        this(DEFAULT_SUBSCRIBER_BUFFER_CAPACITY);
    }

    ExecutionStreamHub(int subscriberBufferCapacity) {
        if (subscriberBufferCapacity < 1) {
            throw new IllegalArgumentException("subscriberBufferCapacity must be positive");
        }
        this.subscriberBufferCapacity = subscriberBufferCapacity;
    }

    /**
     * Opens node-local live observation state. Production callers must invoke this only after
     * unique durable run admission; the Hub does not retain closed run IDs in memory.
     */
    public void openRun(String runId) {
        requireRunId(runId);
        RunStreamState newState = new RunStreamState(runId, subscriberBufferCapacity);
        if (runs.putIfAbsent(runId, newState) != null) {
            throw new IllegalStateException("Execution stream is already open for run " + runId);
        }
    }

    /** Subscribes to future events only; no event history is replayed. */
    public ExecutionStreamSubscription subscribe(String runId) {
        requireRunId(runId);
        RunStreamState state = runs.get(runId);
        if (state == null) {
            throw new IllegalStateException("No open execution stream for run " + runId);
        }
        return state.subscribe();
    }

    /** Closes all subscriptions and removes the run state. */
    public void closeRun(String runId) {
        requireRunId(runId);
        RunStreamState state = runs.get(runId);
        if (state != null) {
            try {
                state.close();
            } finally {
                runs.remove(runId, state);
            }
        }
    }

    public boolean isOpen(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    void publish(String runId, ExecutionStreamPayload payload, Clock clock) {
        RunStreamState state = runs.get(runId);
        if (state != null) {
            state.publish(payload, clock);
        }
    }

    int subscriberCount(String runId) {
        RunStreamState state = runs.get(runId);
        return state == null ? 0 : state.subscriberCount();
    }

    private static void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }
}
