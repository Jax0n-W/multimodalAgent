package com.multimodalAgent.agent.recovery.integration;

import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryCheckpointResumeSessionTest {

    @Test
    void continuesCheckpointSequenceFromTheHydratedLatestCheckpoint() {
        String snapshotId = "exec-config-v1-" + "a".repeat(64);
        ToolCall call = new ToolCall("call-1", "probe", Map.of());
        List<AgentMessage> messages = List.of(
                AgentMessage.user("start"), AgentMessage.assistantToolCalls(List.of(call))
        );
        RecoveryCheckpoint latest = new RecoveryCheckpoint(
                "checkpoint-7", "run", 7, 1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                messages, List.of(), Set.of("call-1"),
                new BudgetCheckpoint(1, 0, 0, 0, 0, Optional.empty(), false),
                Set.of(), snapshotId, Instant.parse("2026-09-29T00:00:00Z"), 1
        );
        RecordingStore store = new RecordingStore();
        AgentRunSpec spec = new AgentRunSpec("run", "session", messages, 3);
        RecoveryCheckpointSession session = new RecoveryCheckpointSession(
                spec, latest,
                new BudgetUsage(1, 0, 0, 0, 0, Optional.empty(), false),
                Optional.empty(), store,
                Clock.fixed(Instant.parse("2026-09-29T00:00:01Z"), ZoneOffset.UTC)
        );

        session.afterToolOutcome(
                1, "call-1", "probe",
                ToolResult.success("ok", ToolPolicyDecision.allow())
        );

        assertEquals(8, store.persisted.get(0).sequence());
        assertEquals(RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                store.persisted.get(0).boundary());
    }

    private static final class RecordingStore implements RecoveryCheckpointStore {
        private final List<RecoveryCheckpoint> persisted = new ArrayList<>();
        @Override public void persist(RecoveryCheckpoint checkpoint) { persisted.add(checkpoint); }
        @Override public Optional<RecoveryCheckpoint> findLatestByRunId(String runId) { return Optional.empty(); }
        @Override public Optional<RecoveryCheckpoint> findByCheckpointId(String id) { return Optional.empty(); }
        @Override public List<RecoveryCheckpoint> listByRunId(String runId) { return List.copyOf(persisted); }
    }
}
