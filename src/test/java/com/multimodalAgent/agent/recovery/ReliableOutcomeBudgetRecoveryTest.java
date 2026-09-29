package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.budget.BudgetBlockReason;
import com.multimodalAgent.agent.runtime.budget.BudgetDimension;
import com.multimodalAgent.agent.runtime.budget.BudgetSession;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableOutcomeBudgetRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-29T04:00:00Z");

    @Test
    void startedModelAndToolCallsSurviveCrashWithoutResettingCheckpointCounters() {
        RecoveryCheckpoint checkpoint = checkpoint(new BudgetCheckpoint(
                1, 1, 7, 3, 10, Optional.of(new BigDecimal("0.10")), false
        ));
        List<RecoveryModelEvidence> models = List.of(
                model("model-1", RecoveryModelStatus.SUCCEEDED, 1),
                model("model-2", RecoveryModelStatus.RUNNING, 3)
        );
        List<RecoveryToolEvidence> tools = List.of(
                tool("execution-1", "call-1", RecoveryToolStatus.SUCCEEDED, 2),
                tool("execution-2", "call-2", RecoveryToolStatus.STARTED, 4),
                tool("execution-2", "call-2", RecoveryToolStatus.STARTED, 4)
        );

        BudgetUsage recovered = new BudgetRecoveryReconstructor()
                .reconstruct(checkpoint, models, tools)
                .usage();

        assertEquals(2, recovered.modelCalls());
        assertEquals(2, recovered.toolCalls());
        assertEquals(7, recovered.inputTokens());
        assertEquals(3, recovered.outputTokens());
        assertEquals(10, recovered.totalTokens());
        assertTrue(recovered.unknownUsageObserved());
        assertTrue(recovered.cost().isEmpty());
    }

    @Test
    void restorationEnforcesRecoveredCallsAndUnverifiableUsage() {
        BudgetSession callLimited = BudgetSession.restore(
                ExecutionBudget.builder().maxModelCalls(2).build(),
                Optional.empty(),
                new BudgetUsage(2, 0, 0, 0, 0, Optional.empty(), false)
        );
        assertEquals(
                BudgetBlockReason.EXHAUSTED,
                callLimited.admitModelCall().orElseThrow().reason()
        );

        BudgetSession tokenUnknown = BudgetSession.restore(
                ExecutionBudget.builder().maxTotalTokens(100).build(),
                Optional.empty(),
                new BudgetUsage(1, 0, 10, 5, 15, Optional.empty(), true)
        );
        assertEquals(BudgetDimension.TOTAL_TOKENS,
                tokenUnknown.admitToolCall().orElseThrow().dimension());
        assertEquals(BudgetBlockReason.UNVERIFIABLE,
                tokenUnknown.admitToolCall().orElseThrow().reason());

        ModelIdentity identity = new ModelIdentity("openai", "gpt-test");
        BudgetSession costUnknown = BudgetSession.restore(
                ExecutionBudget.builder()
                        .maxCost(BigDecimal.ONE)
                        .pricing(new ModelPricing(identity, BigDecimal.ONE, BigDecimal.ONE))
                        .build(),
                Optional.of(identity),
                new BudgetUsage(1, 0, 2, 1, 3, Optional.empty(), false)
        );
        assertEquals(BudgetDimension.COST,
                costUnknown.admitToolCall().orElseThrow().dimension());
        assertEquals(BudgetBlockReason.UNVERIFIABLE,
                costUnknown.admitToolCall().orElseThrow().reason());
    }

    @Test
    void materializedExactOutcomeAdvancesCheckpointWithoutExecutingTool() {
        RecoveryCheckpoint latest = checkpoint(new BudgetCheckpoint(
                1, 0, 2, 1, 3, Optional.empty(), false
        ));
        ReliableToolOutcome outcome = ReliableToolOutcome.capture(
                "execution-2",
                "run-recovery",
                "call-2",
                "charge_card",
                "{\"receipt\":\"r-17\"}",
                NOW
        );
        List<RecoveryCheckpoint> writes = new ArrayList<>();
        RecoveryCheckpointStore store = new RecoveryCheckpointStore() {
            @Override
            public void persist(RecoveryCheckpoint checkpoint) {
                writes.add(checkpoint);
            }

            @Override
            public Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
                return writes.stream().reduce((left, right) -> right);
            }

            @Override
            public Optional<RecoveryCheckpoint> findByCheckpointId(String checkpointId) {
                return writes.stream()
                        .filter(value -> value.checkpointId().equals(checkpointId))
                        .findFirst();
            }

            @Override
            public List<RecoveryCheckpoint> listByRunId(String runId) {
                return List.copyOf(writes);
            }
        };
        ReliableToolOutcomeCheckpointAdvancer advancer =
                new ReliableToolOutcomeCheckpointAdvancer(
                        runId -> {
                        },
                        store,
                        Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC)
                );
        RecoveredBudgetUsage budget = new RecoveredBudgetUsage(
                new BudgetUsage(1, 1, 2, 1, 3, Optional.empty(), false)
        );

        RecoveryCheckpoint advanced = advancer.advance(latest, outcome, budget);

        assertEquals(RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, advanced.boundary());
        assertEquals(latest.sequence() + 1, advanced.sequence());
        assertEquals("snapshot-1", advanced.runtimeConfigSnapshotId());
        assertEquals(latest.approvedToolCallIds(), advanced.approvedToolCallIds());
        assertEquals(latest.seenToolCallIds(), advanced.seenToolCallIds());
        assertEquals(List.of("charge_card"), advanced.toolsUsed());
        AgentMessage toolMessage = advanced.messages().get(advanced.messages().size() - 1);
        assertEquals(AgentMessageRole.TOOL, toolMessage.role());
        assertEquals(outcome.modelVisibleResult(), toolMessage.content());
        assertEquals(1, writes.size());
        assertSame(advanced, advancer.advance(advanced, outcome, budget));
        assertEquals(1, writes.size());
    }

    private RecoveryCheckpoint checkpoint(BudgetCheckpoint budget) {
        return new RecoveryCheckpoint(
                "checkpoint-1",
                "run-recovery",
                4,
                2,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(AgentMessage.user("do it")),
                List.of(),
                Set.of("call-2"),
                budget,
                Set.of("approved-1"),
                "snapshot-1",
                NOW,
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private RecoveryModelEvidence model(
            String stepId,
            RecoveryModelStatus status,
            int stepIndex
    ) {
        return new RecoveryModelEvidence(stepId, 1, stepIndex, status);
    }

    private RecoveryToolEvidence tool(
            String executionId,
            String toolCallId,
            RecoveryToolStatus status,
            int stepIndex
    ) {
        return new RecoveryToolEvidence(
                executionId,
                "step-" + executionId,
                1,
                stepIndex,
                toolCallId,
                "tool",
                status,
                true
        );
    }
}
