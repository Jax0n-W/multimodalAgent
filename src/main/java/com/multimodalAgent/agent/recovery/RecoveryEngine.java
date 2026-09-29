package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.coordination.CoordinationUnavailableException;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotRestorer;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfig;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.recovery.integration.RecoveryCheckpointingAgentExecutionCoordinator;
import com.multimodalAgent.agent.runtime.AgentResumeState;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.ToolCall;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owns the acquire -> re-evaluate -> repair -> rehydrate -> same-run resume protocol. */
public class RecoveryEngine {

    private final RunLeaseStore leaseStore;
    private final RunLeaseWatchdogFactory watchdogFactory;
    private final RecoveryEvidenceReader evidenceReader;
    private final RecoveryEligibilityEvaluator evaluator;
    private final RecoveryToolRepairerFactory repairerFactory;
    private final BudgetRecoveryReconstructor budgetReconstructor;
    private final ExecutionConfigSnapshotStore snapshotStore;
    private final ExecutionConfigSnapshotRestorer snapshotRestorer;
    private final ResolvedModelConfig currentModelConfig;
    private final RecoveryCheckpointStore checkpointStore;
    private final PersistentAgentExecutionCoordinator persistence;

    public RecoveryEngine(
            RunLeaseStore leaseStore,
            RunLeaseWatchdogFactory watchdogFactory,
            RecoveryEvidenceReader evidenceReader,
            RecoveryEligibilityEvaluator evaluator,
            RecoveryToolRepairerFactory repairerFactory,
            BudgetRecoveryReconstructor budgetReconstructor,
            ExecutionConfigSnapshotStore snapshotStore,
            ExecutionConfigSnapshotRestorer snapshotRestorer,
            ResolvedModelConfig currentModelConfig,
            RecoveryCheckpointStore checkpointStore,
            PersistentAgentExecutionCoordinator persistence
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore must not be null");
        this.watchdogFactory = Objects.requireNonNull(watchdogFactory, "watchdogFactory must not be null");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader must not be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.repairerFactory = Objects.requireNonNull(repairerFactory, "repairerFactory must not be null");
        this.budgetReconstructor = Objects.requireNonNull(budgetReconstructor, "budgetReconstructor must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.snapshotRestorer = Objects.requireNonNull(snapshotRestorer, "snapshotRestorer must not be null");
        this.currentModelConfig = Objects.requireNonNull(currentModelConfig, "currentModelConfig must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
        this.persistence = Objects.requireNonNull(persistence, "persistence must not be null");
    }

    public RecoveryEngineResult recover(RecoveryCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        RunLeaseAcquireResult acquisition;
        try {
            acquisition = leaseStore.tryAcquire(candidate.runId());
        } catch (RuntimeException exception) {
            throw new CoordinationUnavailableException(candidate.runId(), exception);
        }
        if (acquisition instanceof RunLeaseAcquireResult.AlreadyActive) {
            return RecoveryEngineResult.of(RecoveryEngineResult.Status.ALREADY_ACTIVE);
        }
        if (!(acquisition instanceof RunLeaseAcquireResult.Acquired acquired)) {
            throw new CoordinationUnavailableException(candidate.runId());
        }
        if (!candidate.runId().equals(acquired.lease().runId())) {
            throw new CoordinationUnavailableException(candidate.runId());
        }

        RunLeaseSession session = new RunLeaseSession(acquired.lease());
        RunLeaseWatchdog watchdog = null;
        Throwable failure = null;
        try {
            watchdog = Objects.requireNonNull(watchdogFactory.create(session));
            watchdog.start();
            session.assertExecutionAuthority();
            return recoverOwned(candidate, session);
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            cleanup(session, watchdog, failure);
        }
    }

    private RecoveryEngineResult recoverOwned(
            RecoveryCandidate candidate,
            RunLeaseSession session
    ) {
        RecoveryAuthorityGuard authority = runId -> {
            if (!candidate.runId().equals(runId)) {
                throw new IllegalArgumentException("Recovery authority run identity mismatch");
            }
            session.assertExecutionAuthority();
        };
        RecoveryEvidence evidence = evidenceReader.load(candidate.runId());
        authority.assertAuthority(candidate.runId());
        RecoveryDecision decision = evaluator.evaluate(evidence);
        Set<String> retries = Set.of();
        while (decision.disposition() == RecoveryDisposition.REQUIRES_RECONCILIATION) {
            RecoveryRepairResult repair = repairerFactory.create(authority).repair(evidence);
            authority.assertAuthority(candidate.runId());
            if (repair.status() == RecoveryRepairResult.Status.MANUAL) {
                return RecoveryEngineResult.of(
                        RecoveryEngineResult.Status.MANUAL_INTERVENTION
                );
            }
            evidence = evidenceReader.load(candidate.runId());
            authority.assertAuthority(candidate.runId());
            decision = evaluator.evaluate(evidence);
            if (repair.status() == RecoveryRepairResult.Status.RETRY_AUTHORIZED) {
                retries = repair.retryToolCallIds();
                break;
            }
        }
        if (decision.disposition() == RecoveryDisposition.MANUAL_INTERVENTION) {
            return RecoveryEngineResult.of(RecoveryEngineResult.Status.MANUAL_INTERVENTION);
        }
        if (decision.disposition() != RecoveryDisposition.SAFE_TO_RESUME
                && retries.isEmpty()) {
            return RecoveryEngineResult.of(RecoveryEngineResult.Status.NOT_RESUMABLE);
        }

        RecoveryCheckpoint checkpoint = evidence.latestCheckpoint().orElseThrow();
        var recoveredBudget = budgetReconstructor.reconstruct(
                checkpoint, evidence.modelFacts(), evidence.toolFacts()
        );
        String snapshotId = evidence.run().orElseThrow()
                .runtimeConfigSnapshotId().orElseThrow();
        ExecutionConfigSnapshot snapshot = snapshotStore.findById(snapshotId)
                .orElseThrow(() -> new IllegalStateException(
                        "Original runtime config snapshot not found: " + snapshotId
                ));
        ResolvedExecutionConfig config = snapshotRestorer.restore(snapshot);
        if (!config.model().equals(currentModelConfig)) {
            throw new IllegalStateException(
                    "Current Runtime cannot strictly execute the historic model configuration"
            );
        }
        authority.assertAuthority(candidate.runId());

        AgentRunSpec spec = new AgentRunSpec(
                candidate.runId(), candidate.sessionId(), checkpoint.messages(),
                config.runtime().maxIterations(),
                Set.copyOf(config.runtime().allowedTools()),
                checkpoint.approvedToolCallIds(), config.budget()
        );
        AgentResumeState state = resumeState(checkpoint, evidence, retries, recoveredBudget);
        RecoveryExecutionRequest request = new RecoveryExecutionRequest(
                spec, state, snapshotId, CancellationContext.NONE, List.of()
        ).withRuntimeContextContributor(
                ExecutionCoordinationBoundaryMiddleware.sessionContributor(session)
        ).withRuntimeContextContributor(
                RecoveryCheckpointingAgentExecutionCoordinator.resumeSessionContributor(
                        spec, checkpoint, recoveredBudget.usage(),
                        Optional.of(config.model().identity()), checkpointStore
                )
        );
        AgentRunResult result = persistence.resumeExisting(request);
        authority.assertAuthority(candidate.runId());
        return new RecoveryEngineResult(
                RecoveryEngineResult.Status.RESUMED, Optional.of(result)
        );
    }

    private AgentResumeState resumeState(
            RecoveryCheckpoint checkpoint,
            RecoveryEvidence evidence,
            Set<String> retries,
            RecoveredBudgetUsage budget
    ) {
        Set<String> completed = new HashSet<>();
        for (AgentMessage message : checkpoint.messages()) {
            if (message.role() == AgentMessageRole.TOOL) {
                completed.add(message.toolCallId());
            }
        }
        List<ToolCall> pending = new ArrayList<>();
        List<ToolCall> currentCalls = List.of();
        for (int index = checkpoint.messages().size() - 1; index >= 0; index--) {
            AgentMessage message = checkpoint.messages().get(index);
            if (message.role() == AgentMessageRole.ASSISTANT
                    && !message.toolCalls().isEmpty()) {
                currentCalls = message.toolCalls();
                break;
            }
        }
        for (ToolCall call : currentCalls) {
            if (!completed.contains(call.id())
                    && (requested(evidence, call.id()) || retries.contains(call.id())
                    || checkpoint.boundary() == RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)) {
                pending.add(call);
            }
        }
        Set<String> requested = new LinkedHashSet<>();
        evidence.toolFacts().forEach(tool -> requested.add(tool.toolCallId()));
        return new AgentResumeState(
                checkpoint.messages(), Set.copyOf(checkpoint.toolsUsed()),
                checkpoint.seenToolCallIds(), requested, pending,
                checkpoint.iteration(), checkpoint.sequence(), budget.usage()
        );
    }

    private boolean requested(RecoveryEvidence evidence, String toolCallId) {
        return evidence.toolFacts().stream().anyMatch(tool ->
                tool.toolCallId().equals(toolCallId)
                        && tool.status() != RecoveryToolStatus.SUCCEEDED
                        && tool.status() != RecoveryToolStatus.FAILED
                        && tool.status() != RecoveryToolStatus.BLOCKED
                        && tool.status() != RecoveryToolStatus.CANCELLED
        );
    }

    private void cleanup(
            RunLeaseSession session,
            RunLeaseWatchdog watchdog,
            Throwable primary
    ) {
        try {
            if (watchdog != null) {
                watchdog.stop();
            }
        } catch (RuntimeException exception) {
            if (primary != null) primary.addSuppressed(exception);
        }
        try {
            RunLeaseReleaseResult released = leaseStore.release(session.lease());
            if (released == RunLeaseReleaseResult.COORDINATION_UNAVAILABLE
                    && session.state() == RunLeaseState.ACTIVE) {
                session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
            }
        } catch (RuntimeException exception) {
            if (primary != null) primary.addSuppressed(exception);
        } finally {
            session.beginClosing();
            session.close();
        }
    }
}
