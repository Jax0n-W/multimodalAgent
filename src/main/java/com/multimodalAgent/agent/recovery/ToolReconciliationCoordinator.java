package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one ambiguous tool attempt without replaying the tool or changing Runtime terminal truth.
 */
public final class ToolReconciliationCoordinator {

    private final RecoveryAuthorityGuard authorityGuard;
    private final ToolRecoveryStateStore stateStore;
    private final RecoveryEvidenceReader evidenceReader;
    private final ToolAmbiguityPlanner planner;
    private final ToolReconcilerRegistry reconcilerRegistry;
    private final ToolReconciliationAttemptStore attemptStore;

    public ToolReconciliationCoordinator(
            RecoveryAuthorityGuard authorityGuard,
            ToolRecoveryStateStore stateStore,
            RecoveryEvidenceReader evidenceReader,
            ToolAmbiguityPlanner planner,
            ToolReconcilerRegistry reconcilerRegistry,
            ToolReconciliationAttemptStore attemptStore
    ) {
        this.authorityGuard = Objects.requireNonNull(
                authorityGuard,
                "authorityGuard must not be null"
        );
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore must not be null");
        this.evidenceReader = Objects.requireNonNull(
                evidenceReader,
                "evidenceReader must not be null"
        );
        this.planner = Objects.requireNonNull(planner, "planner must not be null");
        this.reconcilerRegistry = Objects.requireNonNull(
                reconcilerRegistry,
                "reconcilerRegistry must not be null"
        );
        this.attemptStore = Objects.requireNonNull(
                attemptStore,
                "attemptStore must not be null"
        );
    }

    public ToolReconciliationResolution resolve(String runId, String toolCallId) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        authorityGuard.assertAuthority(runId);
        ToolUnknownMaterializationResult materialization =
                stateStore.materializeUnknown(runId, toolCallId);
        if (materialization == ToolUnknownMaterializationResult.ALREADY_TERMINAL
                || materialization == ToolUnknownMaterializationResult.NOT_AMBIGUOUS) {
            return new ToolReconciliationResolution(
                    materialization,
                    Optional.empty(),
                    ToolReconciliationResolutionStatus.NOT_AMBIGUOUS,
                    Optional.empty()
            );
        }

        authorityGuard.assertAuthority(runId);
        RecoveryToolEvidence evidence = requireEvidence(runId, toolCallId);
        ToolAmbiguityPlan plan = planner.plan(evidence);
        if (plan.action() != ToolAmbiguityAction.RECONCILE) {
            return new ToolReconciliationResolution(
                    materialization,
                    Optional.of(plan),
                    ToolReconciliationResolutionStatus.DEFERRED,
                    Optional.empty()
            );
        }

        String strategyId = plan.reconciliationStrategyId().orElseThrow();
        ToolReconciler reconciler = reconcilerRegistry.require(strategyId);
        ToolRecoveryContractSnapshot contract = evidence.recoveryContract().orElseThrow();
        authorityGuard.assertAuthority(runId);
        ToolReconciliationStart start = attemptStore.start(runId, toolCallId, contract);
        if (start.disposition() == ToolReconciliationStartDisposition.REUSED_DECISIVE) {
            return resolution(
                    materialization,
                    plan,
                    ToolReconciliationResolutionStatus.REUSED,
                    start.attempt()
            );
        }
        if (start.disposition() == ToolReconciliationStartDisposition.ALREADY_IN_PROGRESS) {
            return resolution(
                    materialization,
                    plan,
                    ToolReconciliationResolutionStatus.IN_PROGRESS,
                    start.attempt()
            );
        }
        if (start.disposition() == ToolReconciliationStartDisposition.ALREADY_TERMINAL
                || start.disposition() == ToolReconciliationStartDisposition.NOT_AMBIGUOUS) {
            return new ToolReconciliationResolution(
                    materialization,
                    Optional.empty(),
                    ToolReconciliationResolutionStatus.NOT_AMBIGUOUS,
                    Optional.empty()
            );
        }
        if (start.disposition() != ToolReconciliationStartDisposition.STARTED) {
            throw new IllegalStateException(
                    "Unsupported reconciliation start disposition: "
                            + start.disposition()
            );
        }

        ToolReconciliationAttempt attempt = start.attempt().orElseThrow();
        authorityGuard.assertAuthority(runId);
        ToolReconciliationResult result;
        try {
            result = Objects.requireNonNull(
                    reconciler.reconcile(attempt.context(evidence.toolName())),
                    "ToolReconciler returned a null result"
            );
        } catch (RuntimeException reconciliationFailure) {
            failAttemptIfAuthorized(runId, attempt, reconciliationFailure);
            throw reconciliationFailure(runId, toolCallId, reconciliationFailure);
        }

        authorityGuard.assertAuthority(runId);
        ToolReconciliationAttempt completed;
        try {
            completed = attemptStore.complete(
                    attempt.reconciliationId(),
                    result
            );
            if (completed.status() != ToolReconciliationAttemptStatus.COMPLETED
                    && completed.status() != ToolReconciliationAttemptStatus.SUPERSEDED) {
                throw new IllegalStateException(
                        "Reconciliation completion returned non-terminal attempt state"
                );
            }
        } catch (RuntimeException persistenceFailure) {
            failAttemptIfAuthorized(runId, attempt, persistenceFailure);
            throw reconciliationFailure(runId, toolCallId, persistenceFailure);
        }
        ToolReconciliationResolutionStatus status = completed.status()
                == ToolReconciliationAttemptStatus.SUPERSEDED
                ? ToolReconciliationResolutionStatus.SUPERSEDED
                : ToolReconciliationResolutionStatus.COMPLETED;
        return resolution(materialization, plan, status, Optional.of(completed));
    }

    private RecoveryToolEvidence requireEvidence(String runId, String toolCallId) {
        RecoveryEvidence evidence = evidenceReader.load(runId);
        if (!evidence.issues().isEmpty()) {
            throw new IllegalStateException("Recovery evidence is not trustworthy for " + runId);
        }
        return evidence.toolFacts().stream()
                .filter(tool -> tool.toolCallId().equals(toolCallId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Recovery tool evidence not found for " + runId + "/" + toolCallId
                ));
    }

    private void failAttemptIfAuthorized(
            String runId,
            ToolReconciliationAttempt attempt,
            RuntimeException failure
    ) {
        try {
            authorityGuard.assertAuthority(runId);
        } catch (RuntimeException authorityFailure) {
            failure.addSuppressed(authorityFailure);
            return;
        }
        try {
            attemptStore.fail(
                    attempt.reconciliationId(),
                    "RECONCILIATION_FAILED",
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage()
            );
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private ToolReconciliationException reconciliationFailure(
            String runId,
            String toolCallId,
            RuntimeException failure
    ) {
        return new ToolReconciliationException(
                "Tool reconciliation failed for " + runId + "/" + toolCallId,
                failure
        );
    }

    private ToolReconciliationResolution resolution(
            ToolUnknownMaterializationResult materialization,
            ToolAmbiguityPlan plan,
            ToolReconciliationResolutionStatus status,
            Optional<ToolReconciliationAttempt> attempt
    ) {
        return new ToolReconciliationResolution(
                materialization,
                Optional.of(plan),
                status,
                attempt
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
