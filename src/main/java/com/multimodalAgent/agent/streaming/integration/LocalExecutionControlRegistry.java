package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.runtime.control.RuntimeCancellationBoundary;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Node-local active cancellation entries; durable identity and ownership remain in MySQL. */
@Component
public final class LocalExecutionControlRegistry {

    private static final System.Logger LOGGER = System.getLogger(
            LocalExecutionControlRegistry.class.getName()
    );

    private final ConcurrentMap<String, Entry> active = new ConcurrentHashMap<>();
    private final ExecutionStreamPublisher publisher;

    public LocalExecutionControlRegistry(ExecutionStreamPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public Entry create(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        return new Entry(runId, publisher);
    }

    /** Called only by the post-admission Runtime context contributor. */
    public void register(Entry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (active.putIfAbsent(entry.runId, entry) != null) {
            throw new IllegalStateException("Execution control is already active for " + entry.runId);
        }
    }

    public CancelRequestResult requestCancel(String runId) {
        Entry entry = active.get(runId);
        return entry == null ? CancelRequestResult.NOT_ACTIVE : entry.requestCancel();
    }

    public void close(Entry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entry.close();
        active.remove(entry.runId, entry);
    }

    public int activeCount() {
        return active.size();
    }

    /** Intent and local lifecycle use separate axes under one terminal/cancel monitor. */
    public static final class Entry implements RuntimeCancellationBoundary {

        private final String runId;
        private final ExecutionStreamPublisher publisher;
        private ExecutionControlState intent = ExecutionControlState.RUNNING;
        private Lifecycle lifecycle = Lifecycle.ACTIVE;

        private Entry(String runId, ExecutionStreamPublisher publisher) {
            this.runId = runId;
            this.publisher = publisher;
        }

        @Override
        public synchronized ExecutionControlState state() {
            return intent;
        }

        @Override
        public synchronized CancelRequestResult requestCancel() {
            if (lifecycle == Lifecycle.CORE_TERMINAL) {
                return CancelRequestResult.ALREADY_TERMINAL;
            }
            if (lifecycle == Lifecycle.CLOSED) {
                return CancelRequestResult.NOT_ACTIVE;
            }
            if (intent == ExecutionControlState.CANCEL_REQUESTED) {
                return CancelRequestResult.ALREADY_REQUESTED;
            }
            intent = ExecutionControlState.CANCEL_REQUESTED;
            try {
                publisher.publish(runId, new ControlEvent(ExecutionControlState.CANCEL_REQUESTED));
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Cancellation observation failed for run {0}: {1}",
                        runId, exception.getClass().getSimpleName());
            }
            return CancelRequestResult.ACCEPTED;
        }

        @Override
        public synchronized boolean trySealNormalCompletion() {
            if (lifecycle != Lifecycle.ACTIVE) {
                throw new IllegalStateException("Execution is not active");
            }
            if (intent == ExecutionControlState.CANCEL_REQUESTED) {
                return false;
            }
            lifecycle = Lifecycle.CORE_TERMINAL;
            return true;
        }

        @Override
        public synchronized void sealCoreTerminal() {
            if (lifecycle == Lifecycle.ACTIVE) {
                lifecycle = Lifecycle.CORE_TERMINAL;
            }
        }

        private synchronized void close() {
            lifecycle = Lifecycle.CLOSED;
        }

        private enum Lifecycle {
            ACTIVE,
            CORE_TERMINAL,
            CLOSED
        }
    }
}
