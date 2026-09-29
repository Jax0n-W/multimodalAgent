package com.multimodalAgent.agent.recovery;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** P10.4/P10.5 repair policy executed only under a P7 recovery lease. */
public final class RecoveryToolRepairer {

    private final RecoveryAuthorityGuard authority;
    private final RecoveryEvidenceReader evidenceReader;
    private final ReliableToolOutcomeStore outcomeStore;
    private final ReliableToolOutcomeMaterializer materializer;
    private final RecoveryCheckpointStore checkpointStore;
    private final ToolReconciliationAttemptStore attemptStore;
    private final ToolReconciliationCoordinator reconciliation;
    private final BudgetRecoveryReconstructor budgetReconstructor;
    private final Clock clock;

    public RecoveryToolRepairer(
            RecoveryAuthorityGuard authority,
            RecoveryEvidenceReader evidenceReader,
            ReliableToolOutcomeStore outcomeStore,
            ReliableToolOutcomeMaterializer materializer,
            RecoveryCheckpointStore checkpointStore,
            ToolReconciliationAttemptStore attemptStore,
            ToolReconciliationCoordinator reconciliation,
            BudgetRecoveryReconstructor budgetReconstructor,
            Clock clock
    ) {
        this.authority = Objects.requireNonNull(authority, "authority must not be null");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader must not be null");
        this.outcomeStore = Objects.requireNonNull(outcomeStore, "outcomeStore must not be null");
        this.materializer = Objects.requireNonNull(materializer, "materializer must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.attemptStore = Objects.requireNonNull(attemptStore, "attemptStore must not be null");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation must not be null");
        this.budgetReconstructor = Objects.requireNonNull(budgetReconstructor, "budgetReconstructor must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RecoveryRepairResult repair(RecoveryEvidence evidence) {
        Set<String> retries = new LinkedHashSet<>();
        for (RecoveryToolEvidence tool : evidence.toolFacts()) {
            if (tool.status() != RecoveryToolStatus.STARTED
                    && tool.status() != RecoveryToolStatus.UNKNOWN) {
                continue;
            }
            authority.assertAuthority(evidence.runId());
            attemptStore.abandonStarted(evidence.runId(), tool.toolCallId());
            var exact = outcomeStore.findByRunIdAndToolCallId(
                    evidence.runId(), tool.toolCallId()
            );
            if (exact.isPresent()) {
                materializer.materialize(evidence.runId(), tool.toolCallId());
                RecoveryEvidence refreshed = evidenceReader.load(evidence.runId());
                RecoveryCheckpoint latest = refreshed.latestCheckpoint().orElseThrow();
                RecoveredBudgetUsage budget = budgetReconstructor.reconstruct(
                        latest, refreshed.modelFacts(), refreshed.toolFacts()
                );
                new ReliableToolOutcomeCheckpointAdvancer(authority, checkpointStore, clock)
                        .advance(latest, exact.orElseThrow(), budget);
                return new RecoveryRepairResult(
                        RecoveryRepairResult.Status.REEVALUATE, Set.of()
                );
            }

            ToolReconciliationResolution resolution = reconciliation.resolve(
                    evidence.runId(), tool.toolCallId()
            );
            if (resolution.status() == ToolReconciliationResolutionStatus.DEFERRED) {
                if (retryable(tool)) {
                    retries.add(tool.toolCallId());
                    continue;
                }
                return manual();
            }
            if (resolution.attempt().isEmpty()) {
                return manual();
            }
            ToolReconciliationAttempt attempt = resolution.attempt().orElseThrow();
            ToolReconciliationOutcome outcome = attempt.outcome().orElse(null);
            if (outcome == ToolReconciliationOutcome.CONFIRMED_NOT_APPLIED && retryable(tool)) {
                retries.add(tool.toolCallId());
                continue;
            }
            // CONFIRMED_APPLIED without an exact result can never synthesize model-visible truth.
            return manual();
        }
        return retries.isEmpty()
                ? new RecoveryRepairResult(RecoveryRepairResult.Status.REEVALUATE, Set.of())
                : new RecoveryRepairResult(
                        RecoveryRepairResult.Status.RETRY_AUTHORIZED, retries
                );
    }

    private boolean retryable(RecoveryToolEvidence tool) {
        return tool.recoveryContract()
                .map(ToolRecoveryContractSnapshot::replaySemantics)
                .map(value -> value == ToolReplaySemantics.REPLAY_SAFE
                        || value == ToolReplaySemantics.IDEMPOTENT)
                .orElse(false);
    }

    private RecoveryRepairResult manual() {
        return new RecoveryRepairResult(RecoveryRepairResult.Status.MANUAL, Set.of());
    }
}
