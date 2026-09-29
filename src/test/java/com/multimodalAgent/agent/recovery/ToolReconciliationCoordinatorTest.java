package com.multimodalAgent.agent.recovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReconciliationCoordinatorTest {

    private static final String RUN_ID = "run";
    private static final String TOOL_CALL_ID = "call";
    private static final String TOOL_NAME = "payment";
    private static final String STRATEGY = "payment-lookup-v1";

    @Test
    void missingAuthorityPreventsUnknownMutation() {
        AtomicInteger mutations = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                    throw new IllegalStateException("no authority");
                },
                (runId, toolCallId) -> {
                    mutations.incrementAndGet();
                    return ToolUnknownMaterializationResult.MATERIALIZED_UNKNOWN;
                },
                evidenceReader(),
                new FakeAttemptStore(),
                List.of(new NamedReconciler(new AtomicInteger(), false))
        );

        assertThrows(IllegalStateException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(0, mutations.get());
    }

    @Test
    void lostAuthorityAfterMaterializationFailsClosedBeforeReconciler() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger reconciliations = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                    if (checks.incrementAndGet() == 2) {
                        throw new IllegalStateException("authority lost");
                    }
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.MATERIALIZED_UNKNOWN,
                evidenceReader(),
                new FakeAttemptStore(),
                List.of(new NamedReconciler(reconciliations, false))
        );

        assertThrows(IllegalStateException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(0, reconciliations.get());
    }

    @Test
    void missingReconcilerFailsClosedBeforeAttemptStarts() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of()
        );

        assertThrows(ToolReconcilerUnavailableException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(0, attempts.starts.get());
    }

    @ParameterizedTest
    @EnumSource(value = ToolReconciliationStartDisposition.class, names = {
            "ALREADY_TERMINAL", "NOT_AMBIGUOUS"
    })
    void updatedRuntimeTruthInvalidatesEarlierReconciliationPlan(
            ToolReconciliationStartDisposition disposition
    ) {
        FakeAttemptStore attempts = new FakeAttemptStore();
        attempts.startDisposition = disposition;
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.NOT_AMBIGUOUS, resolution.status());
        assertTrue(resolution.plan().isEmpty());
        assertTrue(resolution.attempt().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void missingPersistedContractDefersWithoutAttemptOrReconciler() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        AtomicInteger calls = new AtomicInteger();
        RecoveryEvidenceReader missingContract = runId -> new RecoveryEvidence(
                runId,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(new RecoveryToolEvidence(
                        "execution",
                        "step",
                        1,
                        1,
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        RecoveryToolStatus.UNKNOWN,
                        true,
                        Optional.empty()
                )),
                List.of()
        );
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                missingContract,
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.DEFERRED, resolution.status());
        assertEquals(ToolAmbiguityAction.CONTRACT_UNAVAILABLE,
                resolution.plan().orElseThrow().action());
        assertEquals(0, attempts.starts.get());
        assertEquals(0, calls.get());
    }

    @Test
    void attemptStartsBeforeObservationAndReconcilerFailureIsDurable() {
        List<String> sequence = new ArrayList<>();
        FakeAttemptStore attempts = new FakeAttemptStore(sequence);
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new ToolReconciler() {
                    @Override
                    public String strategyId() {
                        return STRATEGY;
                    }

                    @Override
                    public ToolReconciliationResult reconcile(
                            ToolReconciliationContext context
                    ) {
                        calls.incrementAndGet();
                        sequence.add("RECONCILER_CALLED");
                        throw new IllegalStateException("external lookup unavailable");
                    }
                })
        );

        assertThrows(ToolReconciliationException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(1, calls.get());
        assertEquals(List.of("ATTEMPT_STARTED", "RECONCILER_CALLED", "ATTEMPT_FAILED"),
                sequence);
    }

    @Test
    void authorityLossAfterAttemptStartDoesNotFailAttemptOrCallReconciler() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        FakeAttemptStore attempts = new FakeAttemptStore();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                    if (checks.incrementAndGet() == 4) {
                        throw new IllegalStateException("authority lost before observation");
                    }
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );

        assertEquals("authority lost before observation", failure.getMessage());
        assertEquals(1, attempts.starts.get());
        assertEquals(0, attempts.failures.get());
        assertEquals(0, attempts.completions.get());
        assertEquals(ToolReconciliationAttemptStatus.STARTED, attempts.durableStatus);
        assertEquals(0, calls.get());
    }

    @Test
    void authorityLossAfterObservationDoesNotPersistResultOrFailAttempt() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        FakeAttemptStore attempts = new FakeAttemptStore();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                    if (checks.incrementAndGet() == 5) {
                        throw new IllegalStateException("authority lost before completion");
                    }
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );

        assertEquals("authority lost before completion", failure.getMessage());
        assertEquals(1, calls.get());
        assertEquals(0, attempts.completions.get());
        assertEquals(0, attempts.failures.get());
        assertEquals(ToolReconciliationAttemptStatus.STARTED, attempts.durableStatus);
    }

    @Test
    void reconcilerFailureIsNotPersistedAfterAuthorityLoss() {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        FakeAttemptStore attempts = new FakeAttemptStore();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                    if (checks.incrementAndGet() == 5) {
                        throw new IllegalStateException("authority lost during cleanup");
                    }
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, true))
        );

        ToolReconciliationException failure = assertThrows(
                ToolReconciliationException.class,
                () -> coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );

        assertEquals("failed", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("authority lost during cleanup",
                failure.getCause().getSuppressed()[0].getMessage());
        assertEquals(1, calls.get());
        assertEquals(0, attempts.failures.get());
        assertEquals(ToolReconciliationAttemptStatus.STARTED, attempts.durableStatus);
    }

    @Test
    void decisiveAttemptIsReusedWithoutCallingReconciler() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        attempts.startDisposition = ToolReconciliationStartDisposition.REUSED_DECISIVE;
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.REUSED, resolution.status());
        assertEquals(0, calls.get());
        assertTrue(resolution.attempt().isPresent());
    }

    @Test
    void existingStartedAttemptRemainsInProgressWithoutAnotherObservation() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        attempts.startDisposition = ToolReconciliationStartDisposition.ALREADY_IN_PROGRESS;
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.IN_PROGRESS, resolution.status());
        assertTrue(resolution.attempt().isPresent());
        assertEquals(ToolReconciliationAttemptStatus.STARTED,
                resolution.attempt().orElseThrow().status());
        assertEquals(1, attempts.starts.get());
        assertEquals(0, calls.get());
    }

    @Test
    void resultPersistenceFailureFailsAttemptWithoutChangingRuntimeTruth() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        attempts.completeFailure = true;
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger stateMutations = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> {
                    stateMutations.incrementAndGet();
                    return ToolUnknownMaterializationResult.ALREADY_UNKNOWN;
                },
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        assertThrows(ToolReconciliationException.class, () ->
                coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(1, calls.get());
        assertEquals(1, attempts.failures.get());
        assertEquals(1, stateMutations.get());
        assertEquals(ToolReconciliationAttemptStatus.FAILED, attempts.durableStatus);
    }

    @Test
    void doublePersistenceFailurePreservesBothFailuresAndMayLeaveAttemptStarted() {
        FakeAttemptStore attempts = new FakeAttemptStore();
        attempts.completeFailure = true;
        attempts.failFailure = true;
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(
                ignored -> {
                },
                (runId, toolCallId) -> ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                evidenceReader(),
                attempts,
                List.of(new NamedReconciler(calls, false))
        );

        ToolReconciliationException failure = assertThrows(
                ToolReconciliationException.class,
                () -> coordinator.resolve(RUN_ID, TOOL_CALL_ID)
        );

        assertEquals("result persistence unavailable", failure.getCause().getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals("failure persistence unavailable",
                failure.getCause().getSuppressed()[0].getMessage());
        assertEquals(1, calls.get());
        assertEquals(1, attempts.completions.get());
        assertEquals(1, attempts.failures.get());
        assertEquals(ToolReconciliationAttemptStatus.STARTED, attempts.durableStatus);
    }

    private ToolReconciliationCoordinator coordinator(
            RecoveryAuthorityGuard guard,
            ToolRecoveryStateStore stateStore,
            RecoveryEvidenceReader evidenceReader,
            ToolReconciliationAttemptStore attempts,
            List<ToolReconciler> reconcilers
    ) {
        return new ToolReconciliationCoordinator(
                guard,
                stateStore,
                evidenceReader,
                new ToolAmbiguityPlanner(),
                new ToolReconcilerRegistry(reconcilers),
                attempts
        );
    }

    private RecoveryEvidenceReader evidenceReader() {
        ToolRecoveryContractSnapshot contract = new ToolRecoveryContract(
                "v1",
                ToolReplaySemantics.NON_REPLAYABLE,
                ToolReconciliationSupport.SUPPORTED,
                Optional.of(STRATEGY)
        ).snapshot(TOOL_NAME);
        return runId -> new RecoveryEvidence(
                runId,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(new RecoveryToolEvidence(
                        "execution",
                        "step",
                        1,
                        1,
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        RecoveryToolStatus.UNKNOWN,
                        true,
                        Optional.of(contract)
                )),
                List.of()
        );
    }

    private static final class NamedReconciler implements ToolReconciler {

        private final AtomicInteger calls;
        private final boolean fail;

        private NamedReconciler(AtomicInteger calls, boolean fail) {
            this.calls = calls;
            this.fail = fail;
        }

        @Override
        public String strategyId() {
            return STRATEGY;
        }

        @Override
        public ToolReconciliationResult reconcile(ToolReconciliationContext context) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("failed");
            }
            return ToolReconciliationResult.of(ToolReconciliationOutcome.CONFIRMED_APPLIED);
        }
    }

    private static final class FakeAttemptStore implements ToolReconciliationAttemptStore {

        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger completions = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final List<String> sequence;
        private ToolReconciliationStartDisposition startDisposition =
                ToolReconciliationStartDisposition.STARTED;
        private ToolReconciliationAttemptStatus durableStatus;
        private boolean completeFailure;
        private boolean failFailure;

        private FakeAttemptStore() {
            this(new ArrayList<>());
        }

        private FakeAttemptStore(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public ToolReconciliationStart start(
                String runId,
                String toolCallId,
                ToolRecoveryContractSnapshot contract
        ) {
            starts.incrementAndGet();
            sequence.add("ATTEMPT_STARTED");
            if (startDisposition == ToolReconciliationStartDisposition.ALREADY_TERMINAL
                    || startDisposition == ToolReconciliationStartDisposition.NOT_AMBIGUOUS) {
                return ToolReconciliationStart.withoutAttempt(startDisposition);
            }
            durableStatus = startDisposition
                    == ToolReconciliationStartDisposition.REUSED_DECISIVE
                    ? ToolReconciliationAttemptStatus.COMPLETED
                    : ToolReconciliationAttemptStatus.STARTED;
            ToolReconciliationAttempt attempt = attempt(
                    durableStatus,
                    startDisposition == ToolReconciliationStartDisposition.REUSED_DECISIVE
                            ? Optional.of(ToolReconciliationOutcome.CONFIRMED_APPLIED)
                            : Optional.empty()
            );
            return new ToolReconciliationStart(startDisposition, Optional.of(attempt));
        }

        @Override
        public ToolReconciliationAttempt complete(
                String reconciliationId,
                ToolReconciliationResult result
        ) {
            completions.incrementAndGet();
            if (completeFailure) {
                throw new IllegalStateException("result persistence unavailable");
            }
            durableStatus = ToolReconciliationAttemptStatus.COMPLETED;
            return attempt(ToolReconciliationAttemptStatus.COMPLETED,
                    Optional.of(result.outcome()));
        }

        @Override
        public ToolReconciliationAttempt fail(
                String reconciliationId,
                String errorCode,
                String errorMessage
        ) {
            failures.incrementAndGet();
            sequence.add("ATTEMPT_FAILED");
            if (failFailure) {
                throw new IllegalStateException("failure persistence unavailable");
            }
            durableStatus = ToolReconciliationAttemptStatus.FAILED;
            return attempt(ToolReconciliationAttemptStatus.FAILED, Optional.empty());
        }

        private ToolReconciliationAttempt attempt(
                ToolReconciliationAttemptStatus status,
                Optional<ToolReconciliationOutcome> outcome
        ) {
            return new ToolReconciliationAttempt(
                    "reconciliation",
                    RUN_ID,
                    "execution",
                    TOOL_CALL_ID,
                    1,
                    "contract",
                    STRATEGY,
                    status,
                    outcome,
                    Optional.empty(),
                    Optional.empty(),
                    status == ToolReconciliationAttemptStatus.FAILED
                            ? Optional.of("RECONCILIATION_FAILED")
                            : Optional.empty(),
                    status == ToolReconciliationAttemptStatus.FAILED
                            ? Optional.of("failed")
                            : Optional.empty(),
                    Instant.now(),
                    status == ToolReconciliationAttemptStatus.STARTED
                            ? Optional.empty()
                            : Optional.of(Instant.now())
            );
        }
    }
}
