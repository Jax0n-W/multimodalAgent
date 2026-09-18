package com.multimodalAgent.agent.runtime.control;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionControlTest {

    @Test
    void newControlStartsRunning() {
        ExecutionControl control = ExecutionControl.create();

        assertEquals(ExecutionControlState.RUNNING, control.state());
        assertFalse(control.isCancellationRequested());
    }

    @Test
    void requestCancelTransitionsToCancelRequested() {
        ExecutionControl control = ExecutionControl.create();

        assertEquals(CancelRequestResult.ACCEPTED, control.requestCancel());
        assertEquals(ExecutionControlState.CANCEL_REQUESTED, control.state());
        assertTrue(control.isCancellationRequested());
    }

    @Test
    void repeatedRequestCancelIsIdempotent() {
        ExecutionControl control = ExecutionControl.create();

        assertEquals(CancelRequestResult.ACCEPTED, control.requestCancel());
        assertEquals(CancelRequestResult.ALREADY_REQUESTED, control.requestCancel());
        assertEquals(CancelRequestResult.ALREADY_REQUESTED, control.requestCancel());
        assertEquals(ExecutionControlState.CANCEL_REQUESTED, control.state());
    }

    @Test
    void controlCanBePassedThroughTheExistingCancellationContext() {
        ExecutionControl control = ExecutionControl.create();
        CancellationContext context = control;

        assertSame(control, context);
        control.requestCancel();
        assertTrue(context.isCancellationRequested());
    }

    @Test
    void cancelRequestResultVocabularyIsCompleteAndStable() {
        assertEquals(
                java.util.List.of(
                        CancelRequestResult.ACCEPTED,
                        CancelRequestResult.ALREADY_REQUESTED,
                        CancelRequestResult.ALREADY_TERMINAL,
                        CancelRequestResult.NOT_ACTIVE
                ),
                java.util.List.of(CancelRequestResult.values())
        );
    }

    @Test
    void explicitCancellationHasADistinctRuntimeStopReason() {
        assertEquals(AgentStopReason.CANCELLED, AgentStopReason.valueOf("CANCELLED"));
    }

    @Test
    void concurrentCancelRequestsHaveExactlyOneAcceptedTransition() throws Exception {
        int workerCount = 32;
        ExecutionControl control = ExecutionControl.create();
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CyclicBarrier startBarrier = new CyclicBarrier(workerCount);
        List<Future<CancelRequestResult>> futures = new ArrayList<>(workerCount);

        try {
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    startBarrier.await(5, TimeUnit.SECONDS);
                    return control.requestCancel();
                }));
            }

            List<CancelRequestResult> results = new ArrayList<>(workerCount);
            for (Future<CancelRequestResult> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }

            assertEquals(
                    1,
                    results.stream().filter(CancelRequestResult.ACCEPTED::equals).count()
            );
            assertEquals(
                    workerCount - 1L,
                    results.stream()
                            .filter(CancelRequestResult.ALREADY_REQUESTED::equals)
                            .count()
            );
            assertEquals(ExecutionControlState.CANCEL_REQUESTED, control.state());
            assertTrue(control.isCancellationRequested());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
