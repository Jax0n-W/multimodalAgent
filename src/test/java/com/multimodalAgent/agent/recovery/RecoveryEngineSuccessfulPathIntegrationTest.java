package com.multimodalAgent.agent.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotRestorer;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfig;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionHistoryStore;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceComposition;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.recovery.integration.RecoveryCheckpointRuntimeMiddleware;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEngineSuccessfulPathIntegrationTest {

    private static final String RUN_ID = "recovery-engine-success";
    private static final String SESSION_ID = "recovery-session";
    private static final String TOOL_CALL_ID = "durable-call";
    private static final String TOOL_NAME = "durable_tool";
    private static final Instant NOW = Instant.parse("2026-09-29T07:00:00Z");

    @Test
    void repairsCheckpointAndResumesTheExistingRunThroughFinalization() {
        ObjectMapper objectMapper = new ObjectMapper();
        ResolvedModelConfig modelConfig = new ResolvedModelConfig(
                new ModelIdentity("test", "recovery-model"),
                BigDecimal.ZERO,
                128,
                new ModelTimeoutPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2))
        );
        ResolvedExecutionConfig resolvedConfig = new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                modelConfig,
                new ResolvedExecutionConfig.RuntimeConfig(3, List.of(TOOL_NAME)),
                ExecutionBudget.builder().maxToolCalls(2).build()
        );
        ExecutionConfigSnapshot originalSnapshot =
                new ExecutionConfigSnapshotFactory(objectMapper).create(resolvedConfig);
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore(originalSnapshot);
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        checkpoints.persist(initialCheckpoint(originalSnapshot.snapshotId()));
        ReliableToolOutcome exactOutcome = ReliableToolOutcome.capture(
                "durable-execution",
                RUN_ID,
                TOOL_CALL_ID,
                TOOL_NAME,
                "{\"receipt\":\"confirmed\"}",
                NOW.plusSeconds(1)
        );
        RecoveryEvidenceReader evidenceReader = runId -> evidence(
                originalSnapshot.snapshotId(),
                checkpoints.findLatestByRunId(runId).orElseThrow()
        );

        RecordingHistoryStore history = new RecordingHistoryStore(
                RUN_ID, originalSnapshot.snapshotId()
        );
        ExecutionPersistenceComposition persistenceComposition =
                new ExecutionPersistenceComposition(history);
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.finalAnswer("recovered completion")
        );
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                model,
                toolExecutor,
                TestModelToolDefinitionProjector.INSTANCE,
                persistenceComposition.eventPublisher()
        );
        RuntimeMiddlewareChain middleware = new RuntimeMiddlewareChain(List.of(
                new ExecutionCoordinationBoundaryMiddleware(),
                new RecoveryCheckpointRuntimeMiddleware(
                        persistenceComposition::assertHealthy
                ),
                persistenceComposition.boundaryMiddleware()
        ));
        PersistentAgentExecutionCoordinator persistence =
                persistenceComposition.persistentCoordinator(
                        new AgentExecutionCoordinator(runner, middleware)
                );

        RecordingLeaseStore leases = new RecordingLeaseStore();
        RecoveryEngine engine = new RecoveryEngine(
                leases,
                session -> new RunLeaseWatchdog(
                        leases,
                        session,
                        (task, interval) -> () -> {
                        },
                        Duration.ofHours(1)
                ),
                evidenceReader,
                new RecoveryEligibilityEvaluator(),
                authority -> repairer(
                        authority,
                        evidenceReader,
                        checkpoints,
                        exactOutcome
                ),
                new BudgetRecoveryReconstructor(),
                snapshots,
                new ExecutionConfigSnapshotRestorer(objectMapper),
                modelConfig,
                checkpoints,
                persistence
        );

        RecoveryEngineResult result = engine.recover(
                new RecoveryCandidate(RUN_ID, SESSION_ID)
        );

        assertEquals(RecoveryEngineResult.Status.RESUMED, result.status());
        assertEquals(AgentStopReason.COMPLETED,
                result.runResult().orElseThrow().stopReason());
        assertEquals("recovered completion",
                result.runResult().orElseThrow().finalContent());
        assertEquals(1, leases.acquisitions);
        assertEquals(1, leases.releases);
        assertEquals(0, history.admissions);
        assertEquals(1, history.existingRunAssertions);
        assertEquals(1, history.finalizations);
        assertSame(result.runResult().orElseThrow(), history.finalResult);
        assertTrue(history.events.stream().allMatch(event -> event.runId().equals(RUN_ID)));
        assertFalse(history.events.stream().anyMatch(
                event -> event.type() == AgentEventType.RUN_STARTED
        ));
        assertFalse(history.events.stream().anyMatch(
                event -> event.type() == AgentEventType.TOOL_STARTED
        ));
        assertEquals(1, model.requests().size());
        assertEquals(TOOL_CALL_ID,
                model.requests().get(0).get(2).toolCallId());
        assertEquals(exactOutcome.modelVisibleResult(),
                model.requests().get(0).get(2).content());
        assertTrue(checkpoints.listByRunId(RUN_ID).stream().anyMatch(checkpoint ->
                checkpoint.boundary() == RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME
                        && checkpoint.messages().stream().anyMatch(message ->
                        TOOL_CALL_ID.equals(message.toolCallId())
                                && exactOutcome.modelVisibleResult().equals(message.content())
                )
        ));
        assertEquals(0, snapshots.persistCalls);
        assertSame(originalSnapshot, snapshots.snapshot);
    }

    private RecoveryToolRepairer repairer(
            RecoveryAuthorityGuard authority,
            RecoveryEvidenceReader evidenceReader,
            RecoveryCheckpointStore checkpoints,
            ReliableToolOutcome outcome
    ) {
        ReliableToolOutcomeStore outcomes = new ReliableToolOutcomeStore() {
            @Override
            public void persist(ReliableToolOutcome value) {
                throw new AssertionError("recovery must not replace the durable outcome");
            }

            @Override
            public Optional<ReliableToolOutcome> findByExecutionId(String executionId) {
                return outcome.executionId().equals(executionId)
                        ? Optional.of(outcome) : Optional.empty();
            }

            @Override
            public Optional<ReliableToolOutcome> findByRunIdAndToolCallId(
                    String runId,
                    String toolCallId
            ) {
                return outcome.runId().equals(runId)
                        && outcome.toolCallId().equals(toolCallId)
                        ? Optional.of(outcome) : Optional.empty();
            }
        };
        ToolReconciliationAttemptStore attempts = new UnusedAttemptStore();
        ReliableToolOutcomeMaterializer materializer =
                new ReliableToolOutcomeMaterializer(
                        authority,
                        (runId, toolCallId) -> {
                            throw new AssertionError("successful fact must not rematerialize");
                        }
                );
        ToolReconciliationCoordinator reconciliation = new ToolReconciliationCoordinator(
                authority,
                (runId, toolCallId) -> {
                    throw new AssertionError("successful fact must not reconcile");
                },
                evidenceReader,
                new ToolAmbiguityPlanner(),
                new ToolReconcilerRegistry(List.of()),
                attempts
        );
        return new RecoveryToolRepairer(
                authority,
                evidenceReader,
                outcomes,
                materializer,
                checkpoints,
                attempts,
                reconciliation,
                new BudgetRecoveryReconstructor(),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );
    }

    private RecoveryEvidence evidence(String snapshotId, RecoveryCheckpoint checkpoint) {
        return new RecoveryEvidence(
                RUN_ID,
                Optional.of(new RecoveryRunEvidence(
                        RUN_ID,
                        RecoveryRunStatus.RUNNING,
                        1,
                        Optional.of(snapshotId)
                )),
                Optional.of(checkpoint),
                List.of(new RecoveryModelEvidence(
                        "model-step-1", 1, 1, RecoveryModelStatus.SUCCEEDED
                )),
                List.of(new RecoveryToolEvidence(
                        "durable-execution",
                        "tool-step-1",
                        1,
                        2,
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        RecoveryToolStatus.SUCCEEDED,
                        true,
                        1,
                        Optional.empty()
                )),
                List.of()
        );
    }

    private RecoveryCheckpoint initialCheckpoint(String snapshotId) {
        return new RecoveryCheckpoint(
                "checkpoint-before-tool-result",
                RUN_ID,
                1,
                1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(
                        AgentMessage.user("complete the durable operation"),
                        AgentMessage.assistantToolCalls(List.of(new ToolCall(
                                TOOL_CALL_ID, TOOL_NAME, Map.of("id", "17")
                        )))
                ),
                List.of(),
                Set.of(TOOL_CALL_ID),
                new BudgetCheckpoint(1, 0, 0, 0, 0, Optional.empty(), false),
                Set.of(),
                snapshotId,
                NOW,
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private static final class InMemoryCheckpointStore implements RecoveryCheckpointStore {

        private final List<RecoveryCheckpoint> values = new ArrayList<>();

        @Override
        public synchronized void persist(RecoveryCheckpoint checkpoint) {
            values.add(checkpoint);
        }

        @Override
        public synchronized Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
            return values.stream()
                    .filter(value -> value.runId().equals(runId))
                    .max(Comparator.comparingLong(RecoveryCheckpoint::sequence));
        }

        @Override
        public synchronized Optional<RecoveryCheckpoint> findByCheckpointId(
                String checkpointId
        ) {
            return values.stream()
                    .filter(value -> value.checkpointId().equals(checkpointId))
                    .findFirst();
        }

        @Override
        public synchronized List<RecoveryCheckpoint> listByRunId(String runId) {
            return values.stream().filter(value -> value.runId().equals(runId)).toList();
        }
    }

    private static final class RecordingSnapshotStore implements ExecutionConfigSnapshotStore {

        private final ExecutionConfigSnapshot snapshot;
        private int persistCalls;

        private RecordingSnapshotStore(ExecutionConfigSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot value) {
            persistCalls++;
            return snapshot;
        }

        @Override
        public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
            return snapshot.snapshotId().equals(snapshotId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
    }

    private static final class RecordingHistoryStore implements ExecutionHistoryStore {

        private final String expectedRunId;
        private final String expectedSnapshotId;
        private final List<AgentEvent> events = new ArrayList<>();
        private int admissions;
        private int existingRunAssertions;
        private int finalizations;
        private AgentRunResult finalResult;

        private RecordingHistoryStore(String expectedRunId, String expectedSnapshotId) {
            this.expectedRunId = expectedRunId;
            this.expectedSnapshotId = expectedSnapshotId;
        }

        @Override
        public void admit(AgentExecutionRequest request) {
            admissions++;
        }

        @Override
        public void record(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void finalizeRun(String runId, AgentRunResult result) {
            assertEquals(expectedRunId, runId);
            finalizations++;
            finalResult = result;
        }

        @Override
        public void assertExistingRunning(String runId, String runtimeConfigSnapshotId) {
            assertEquals(expectedRunId, runId);
            assertEquals(expectedSnapshotId, runtimeConfigSnapshotId);
            existingRunAssertions++;
        }
    }

    private static final class RecordingLeaseStore implements RunLeaseStore {

        private int acquisitions;
        private int releases;

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            acquisitions++;
            return new RunLeaseAcquireResult.Acquired(
                    new RunLease(runId, "lease-token-1")
            );
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releases++;
            return RunLeaseReleaseResult.RELEASED;
        }
    }

    private static final class UnusedAttemptStore implements ToolReconciliationAttemptStore {

        @Override
        public ToolReconciliationStart start(
                String runId,
                String toolCallId,
                ToolRecoveryContractSnapshot contract
        ) {
            throw new AssertionError("successful fact must not start reconciliation");
        }

        @Override
        public ToolReconciliationAttempt complete(
                String reconciliationId,
                ToolReconciliationResult result
        ) {
            throw new AssertionError("successful fact must not complete reconciliation");
        }

        @Override
        public ToolReconciliationAttempt fail(
                String reconciliationId,
                String errorCode,
                String errorMessage
        ) {
            throw new AssertionError("successful fact must not fail reconciliation");
        }

        @Override
        public int abandonStarted(String runId, String toolCallId) {
            throw new AssertionError("successful fact must not abandon reconciliation");
        }
    }
}
