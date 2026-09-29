package com.multimodalAgent.agent.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotRestorer;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RecoveryEngineOwnershipTest {

    @Test
    void alreadyActiveSkipsBeforeAnyDurableEvidenceReadOrMutation() {
        RecoveryEvidenceReader reader = mock(RecoveryEvidenceReader.class);
        PersistentAgentExecutionCoordinator persistence = mock(
                PersistentAgentExecutionCoordinator.class
        );
        RunLeaseStore store = new RunLeaseStore() {
            @Override public RunLeaseAcquireResult tryAcquire(String runId) {
                return new RunLeaseAcquireResult.AlreadyActive(runId);
            }
            @Override public RunLeaseRenewResult renew(com.multimodalAgent.agent.coordination.RunLease lease) {
                return RunLeaseRenewResult.RENEWED;
            }
            @Override public RunLeaseReleaseResult release(com.multimodalAgent.agent.coordination.RunLease lease) {
                return RunLeaseReleaseResult.RELEASED;
            }
        };
        RecoveryEngine engine = engine(store, reader, persistence);

        RecoveryEngineResult result = engine.recover(
                new RecoveryCandidate("run-active", "session")
        );

        assertEquals(RecoveryEngineResult.Status.ALREADY_ACTIVE, result.status());
        verifyNoInteractions(reader, persistence);
    }

    @Test
    void competingWorkersLeaveAllRecoveryWorkToTheSingleLeaseWinner() {
        AtomicInteger acquisitions = new AtomicInteger();
        RunLeaseStore store = new RunLeaseStore() {
            @Override public RunLeaseAcquireResult tryAcquire(String runId) {
                acquisitions.incrementAndGet();
                return new RunLeaseAcquireResult.AlreadyActive(runId);
            }
            @Override public RunLeaseRenewResult renew(com.multimodalAgent.agent.coordination.RunLease lease) {
                return RunLeaseRenewResult.RENEWED;
            }
            @Override public RunLeaseReleaseResult release(com.multimodalAgent.agent.coordination.RunLease lease) {
                return RunLeaseReleaseResult.RELEASED;
            }
        };
        RecoveryEvidenceReader reader = mock(RecoveryEvidenceReader.class);
        PersistentAgentExecutionCoordinator persistence = mock(
                PersistentAgentExecutionCoordinator.class
        );
        RecoveryEngine engine = engine(store, reader, persistence);

        engine.recover(new RecoveryCandidate("same-run", "session"));
        engine.recover(new RecoveryCandidate("same-run", "session"));

        assertEquals(2, acquisitions.get());
        verifyNoInteractions(reader, persistence);
    }

    private RecoveryEngine engine(
            RunLeaseStore store,
            RecoveryEvidenceReader reader,
            PersistentAgentExecutionCoordinator persistence
    ) {
        ResolvedModelConfig model = new ResolvedModelConfig(
                new ModelIdentity("test", "model"), BigDecimal.ZERO, 1,
                new ModelTimeoutPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1))
        );
        ExecutionConfigSnapshotStore snapshots = new ExecutionConfigSnapshotStore() {
            @Override public com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot persistIfAbsent(
                    com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot snapshot) {
                return snapshot;
            }
            @Override public Optional<com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot> findById(String id) {
                return Optional.empty();
            }
        };
        return new RecoveryEngine(
                store, session -> { throw new AssertionError("watchdog must not start"); },
                reader, new RecoveryEligibilityEvaluator(),
                authority -> { throw new AssertionError("repairer must not be created"); },
                new BudgetRecoveryReconstructor(), snapshots,
                new ExecutionConfigSnapshotRestorer(new ObjectMapper()), model,
                mock(RecoveryCheckpointStore.class), persistence
        );
    }
}
