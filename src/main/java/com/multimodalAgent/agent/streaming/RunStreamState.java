package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamPayload;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Owns sequence allocation and ordered fan-out for exactly one open run stream. */
final class RunStreamState {

    private static final System.Logger LOGGER = System.getLogger(RunStreamState.class.getName());

    private final String runId;
    private final int subscriberBufferCapacity;
    private final RunScopedStreamSequencer sequencer = new RunScopedStreamSequencer();
    private final Set<ExecutionStreamSubscription> subscriptions = new LinkedHashSet<>();
    private boolean closed;

    RunStreamState(String runId, int subscriberBufferCapacity) {
        this.runId = runId;
        this.subscriberBufferCapacity = subscriberBufferCapacity;
    }

    synchronized ExecutionStreamSubscription subscribe() {
        if (closed) {
            throw new IllegalStateException("Execution stream is closed for run " + runId);
        }
        ExecutionStreamSubscription subscription = new ExecutionStreamSubscription(
                runId,
                subscriberBufferCapacity,
                this::detach
        );
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * Linearization point for allocation, envelope construction, and enqueue to every subscriber.
     */
    synchronized void publish(ExecutionStreamPayload payload, Clock clock) {
        if (closed) {
            return;
        }
        Instant occurredAt = clock.instant();
        long streamSequence = sequencer.next();
        ExecutionStreamEvent event = new ExecutionStreamEvent(
                runId,
                streamSequence,
                occurredAt,
                payload.kind(),
                payload
        );
        for (ExecutionStreamSubscription subscription : Set.copyOf(subscriptions)) {
            try {
                subscription.offer(event);
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Execution stream subscriber failed for run {0}: {1}",
                        runId, exception.getClass().getSimpleName());
                detachSafely(subscription);
            }
        }
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ExecutionStreamSubscription subscription : Set.copyOf(subscriptions)) {
            try {
                subscription.complete();
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Execution stream subscriber cleanup failed for run {0}: {1}",
                        runId, exception.getClass().getSimpleName());
            }
        }
        subscriptions.clear();
    }

    synchronized int subscriberCount() {
        return subscriptions.size();
    }

    private synchronized void detach(ExecutionStreamSubscription subscription) {
        subscriptions.remove(subscription);
    }

    private void detachSafely(ExecutionStreamSubscription subscription) {
        subscriptions.remove(subscription);
        try {
            subscription.close();
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Execution stream subscriber termination failed for run {0}: {1}",
                    runId, exception.getClass().getSimpleName());
        }
    }
}
