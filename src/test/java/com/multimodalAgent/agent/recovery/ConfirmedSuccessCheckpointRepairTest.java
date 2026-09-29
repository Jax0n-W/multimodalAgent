package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmedSuccessCheckpointRepairTest {

    private static final Instant NOW = Instant.parse("2026-09-29T06:00:00Z");

    @Test
    void repairsConfirmedSuccessfulToolsInDurableStepOrderWithoutReexecution() {
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        RecoveryCheckpoint initial = checkpoint();
        checkpoints.persist(initial);
        Map<String, ReliableToolOutcome> outcomes = new LinkedHashMap<>();
        outcomes.put("call-a", outcome("execution-a", "call-a", "first-result"));
        outcomes.put("call-b", outcome("execution-b", "call-b", "second-result"));
        List<RecoveryToolEvidence> tools = List.of(
                tool("execution-b", "call-b", 3, RecoveryToolStatus.SUCCEEDED),
                tool("execution-a", "call-a", 2, RecoveryToolStatus.SUCCEEDED)
        );
        RecoveryToolRepairer repairer = repairer(checkpoints, outcomes);

        assertTrue(repairer.repairConfirmedSuccessCheckpointLag(
                evidence(checkpoints.latest(), tools)
        ));
        assertEquals("call-a", lastToolCall(checkpoints.latest()));

        assertTrue(repairer.repairConfirmedSuccessCheckpointLag(
                evidence(checkpoints.latest(), tools)
        ));
        assertEquals("call-b", lastToolCall(checkpoints.latest()));
        assertFalse(repairer.repairConfirmedSuccessCheckpointLag(
                evidence(checkpoints.latest(), tools)
        ));
        assertEquals(3, checkpoints.listByRunId("repair-run").size());
    }

    @Test
    void refusesToSynthesizeSuccessWithoutExactOutcomeOrForFailedFact() {
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        checkpoints.persist(checkpoint());

        assertFalse(repairer(checkpoints, Map.of())
                .repairConfirmedSuccessCheckpointLag(evidence(
                        checkpoints.latest(),
                        List.of(tool(
                                "execution-a", "call-a", 2,
                                RecoveryToolStatus.SUCCEEDED
                        ))
                )));
        assertFalse(repairer(checkpoints, Map.of(
                "call-a", outcome("execution-a", "call-a", "not-used")
        )).repairConfirmedSuccessCheckpointLag(evidence(
                checkpoints.latest(),
                List.of(tool(
                        "execution-a", "call-a", 2,
                        RecoveryToolStatus.FAILED
                ))
        )));
        assertEquals(1, checkpoints.listByRunId("repair-run").size());
    }

    private RecoveryToolRepairer repairer(
            RecoveryCheckpointStore checkpoints,
            Map<String, ReliableToolOutcome> outcomes
    ) {
        RecoveryAuthorityGuard authority = runId -> {
            assertEquals("repair-run", runId);
        };
        RecoveryEvidenceReader unusedReader = runId -> {
            throw new AssertionError("confirmed-success repair must not materialize or reconcile");
        };
        ToolReconciliationAttemptStore attempts = new UnusedAttemptStore();
        ToolReconciliationCoordinator reconciliation = new ToolReconciliationCoordinator(
                authority,
                (runId, toolCallId) -> {
                    throw new AssertionError("reconciliation must not run");
                },
                unusedReader,
                new ToolAmbiguityPlanner(),
                new ToolReconcilerRegistry(List.of()),
                attempts
        );
        return new RecoveryToolRepairer(
                authority,
                unusedReader,
                outcomeStore(outcomes),
                new ReliableToolOutcomeMaterializer(
                        authority,
                        (runId, toolCallId) -> {
                            throw new AssertionError("already-successful fact must not rematerialize");
                        }
                ),
                checkpoints,
                attempts,
                reconciliation,
                new BudgetRecoveryReconstructor(),
                Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
        );
    }

    private RecoveryEvidence evidence(
            RecoveryCheckpoint checkpoint,
            List<RecoveryToolEvidence> tools
    ) {
        return new RecoveryEvidence(
                "repair-run",
                Optional.of(new RecoveryRunEvidence(
                        "repair-run", RecoveryRunStatus.RUNNING, 1,
                        Optional.of("snapshot-1")
                )),
                Optional.of(checkpoint),
                List.of(new RecoveryModelEvidence(
                        "model-step", 1, 1, RecoveryModelStatus.SUCCEEDED
                )),
                tools,
                List.of()
        );
    }

    private RecoveryCheckpoint checkpoint() {
        return new RecoveryCheckpoint(
                "initial-checkpoint",
                "repair-run",
                1,
                1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(
                        AgentMessage.user("repair both"),
                        AgentMessage.assistantToolCalls(List.of(
                                new ToolCall("call-a", "tool", Map.of()),
                                new ToolCall("call-b", "tool", Map.of())
                        ))
                ),
                List.of(),
                Set.of("call-a", "call-b"),
                BudgetCheckpoint.EMPTY,
                Set.of(),
                "snapshot-1",
                NOW,
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private RecoveryToolEvidence tool(
            String executionId,
            String toolCallId,
            int stepIndex,
            RecoveryToolStatus status
    ) {
        return new RecoveryToolEvidence(
                executionId,
                "step-" + executionId,
                1,
                stepIndex,
                toolCallId,
                "tool",
                status,
                true,
                1,
                Optional.empty()
        );
    }

    private ReliableToolOutcome outcome(
            String executionId,
            String toolCallId,
            String result
    ) {
        return ReliableToolOutcome.capture(
                executionId, "repair-run", toolCallId, "tool", result, NOW
        );
    }

    private ReliableToolOutcomeStore outcomeStore(
            Map<String, ReliableToolOutcome> outcomes
    ) {
        return new ReliableToolOutcomeStore() {
            @Override
            public void persist(ReliableToolOutcome outcome) {
                throw new AssertionError("repair must not write a new reliable outcome");
            }

            @Override
            public Optional<ReliableToolOutcome> findByExecutionId(String executionId) {
                return outcomes.values().stream()
                        .filter(value -> value.executionId().equals(executionId))
                        .findFirst();
            }

            @Override
            public Optional<ReliableToolOutcome> findByRunIdAndToolCallId(
                    String runId,
                    String toolCallId
            ) {
                return Optional.ofNullable(outcomes.get(toolCallId));
            }
        };
    }

    private String lastToolCall(RecoveryCheckpoint checkpoint) {
        return checkpoint.messages().get(checkpoint.messages().size() - 1).toolCallId();
    }

    private static final class InMemoryCheckpointStore implements RecoveryCheckpointStore {

        private final List<RecoveryCheckpoint> values = new ArrayList<>();

        @Override
        public void persist(RecoveryCheckpoint checkpoint) {
            values.add(checkpoint);
        }

        @Override
        public Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
            return values.stream()
                    .filter(value -> value.runId().equals(runId))
                    .max(java.util.Comparator.comparingLong(RecoveryCheckpoint::sequence));
        }

        @Override
        public Optional<RecoveryCheckpoint> findByCheckpointId(String checkpointId) {
            return values.stream()
                    .filter(value -> value.checkpointId().equals(checkpointId))
                    .findFirst();
        }

        @Override
        public List<RecoveryCheckpoint> listByRunId(String runId) {
            return values.stream().filter(value -> value.runId().equals(runId)).toList();
        }

        RecoveryCheckpoint latest() {
            return findLatestByRunId("repair-run").orElseThrow();
        }
    }

    private static final class UnusedAttemptStore implements ToolReconciliationAttemptStore {

        @Override
        public ToolReconciliationStart start(
                String runId,
                String toolCallId,
                ToolRecoveryContractSnapshot contract
        ) {
            throw new AssertionError("reconciliation attempt must not start");
        }

        @Override
        public ToolReconciliationAttempt complete(
                String reconciliationId,
                ToolReconciliationResult result
        ) {
            throw new AssertionError("reconciliation attempt must not complete");
        }

        @Override
        public ToolReconciliationAttempt fail(
                String reconciliationId,
                String errorCode,
                String errorMessage
        ) {
            throw new AssertionError("reconciliation attempt must not fail");
        }

        @Override
        public int abandonStarted(String runId, String toolCallId) {
            throw new AssertionError("confirmed success must not abandon an attempt");
        }
    }
}
