package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One isolated live subscription. Every subscriber owns a bounded queue and an asynchronous
 * delivery boundary, so subscriber code never executes on the Runtime publication thread.
 */
public final class ExecutionStreamSubscription implements AutoCloseable {

    private final String runId;
    private final Sinks.Many<ExecutionStreamEvent> sink;
    private final Flux<ExecutionStreamEvent> events;
    private final Consumer<ExecutionStreamSubscription> detach;
    private final AtomicBoolean terminated = new AtomicBoolean();

    ExecutionStreamSubscription(
            String runId,
            int bufferCapacity,
            Consumer<ExecutionStreamSubscription> detach
    ) {
        this.runId = runId;
        this.detach = detach;
        Queue<ExecutionStreamEvent> queue = new ArrayBlockingQueue<>(bufferCapacity);
        this.sink = Sinks.many().unicast().onBackpressureBuffer(queue);
        this.events = sink.asFlux()
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic(), 1)
                .doFinally(ignored -> terminate(false, null));
    }

    /** Returns the live-only event stream for this subscriber. */
    public Flux<ExecutionStreamEvent> events() {
        return events;
    }

    void offer(ExecutionStreamEvent event) {
        if (terminated.get()) {
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isSuccess()) {
            return;
        }
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
            terminate(true, new SlowExecutionStreamSubscriberException(runId));
        } else {
            terminate(false, null);
        }
    }

    void complete() {
        terminate(true, null);
    }

    /** Unsubscribes this consumer only; it never changes execution-control state. */
    @Override
    public void close() {
        terminate(true, null);
    }

    boolean isTerminated() {
        return terminated.get();
    }

    private void terminate(boolean signalSink, RuntimeException failure) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
        detach.accept(this);
        if (!signalSink) {
            return;
        }
        if (failure == null) {
            sink.tryEmitComplete();
        } else {
            sink.tryEmitError(failure);
        }
    }
}
