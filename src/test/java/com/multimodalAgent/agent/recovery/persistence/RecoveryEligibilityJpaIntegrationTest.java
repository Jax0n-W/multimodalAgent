package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentRuntimeConfigSnapshotEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRecoveryCheckpointRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRuntimeConfigSnapshotRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryDecision;
import com.multimodalAgent.agent.recovery.RecoveryDisposition;
import com.multimodalAgent.agent.recovery.RecoveryEligibilityEvaluator;
import com.multimodalAgent.agent.recovery.RecoveryEvidence;
import com.multimodalAgent.agent.recovery.RecoveryReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10-eligibility;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaRecoveryCheckpointStore.class, JpaRecoveryEvidenceReader.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class RecoveryEligibilityJpaIntegrationTest {

    private static final String RUN_ID = "eligibility-jpa-run";
    private static final String SNAPSHOT_ID = "cfg-eligibility-jpa";
    private static final String STEP_ID = "tool-step-jpa";
    private static final String TOOL_CALL_ID = "call-jpa";

    @Autowired
    private AgentRecoveryCheckpointRepository checkpointRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentRuntimeConfigSnapshotRepository snapshotRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolRepository;

    @Autowired
    private JpaRecoveryCheckpointStore checkpointStore;

    @Autowired
    private JpaRecoveryEvidenceReader evidenceReader;

    private final RecoveryEligibilityEvaluator evaluator = new RecoveryEligibilityEvaluator();

    @BeforeEach
    void setUp() {
        checkpointRepository.deleteAll();
        toolRepository.deleteAll();
        stepRepository.deleteAll();
        runRepository.deleteAll();
        snapshotRepository.deleteAll();

        snapshotRepository.saveAndFlush(new AgentRuntimeConfigSnapshotEntity(
                SNAPSHOT_ID, 1, "c".repeat(64), "{\"model\":\"test\"}"
        ));
        AgentRunEntity run = new AgentRunEntity(
                RUN_ID,
                "request-eligibility",
                7L,
                "session-eligibility",
                AgentRunStatus.RUNNING,
                AgentRunPhase.AWAITING_TOOL
        );
        run.setCurrentIteration(1);
        run.setRuntimeConfigSnapshotId(SNAPSHOT_ID);
        runRepository.saveAndFlush(run);

        stepRepository.saveAndFlush(new AgentStepEntity(
                "model-step-jpa",
                RUN_ID,
                1,
                1,
                AgentStepType.MODEL,
                AgentStepStatus.SUCCEEDED
        ));
        stepRepository.saveAndFlush(new AgentStepEntity(
                STEP_ID,
                RUN_ID,
                1,
                2,
                AgentStepType.TOOL,
                AgentStepStatus.PLANNED
        ));
        toolRepository.saveAndFlush(new ToolExecutionEntity(
                "tool-execution-jpa",
                RUN_ID,
                STEP_ID,
                TOOL_CALL_ID,
                "knowledge_search",
                ToolExecutionStatus.PLANNED
        ));
        checkpointStore.persist(checkpoint());
    }

    @Test
    void readsRealDurableEvidenceAndClassifiesWithoutMutation() {
        long runCount = runRepository.count();
        long stepCount = stepRepository.count();
        long toolCount = toolRepository.count();
        long checkpointCount = checkpointRepository.count();

        RecoveryEvidence evidence = evidenceReader.load(RUN_ID);
        RecoveryDecision decision = evaluator.evaluate(evidence);

        assertEquals(RecoveryDisposition.SAFE_TO_RESUME, decision.disposition());
        assertEquals(RecoveryReason.ELIGIBLE, decision.primaryReason());
        assertEquals("checkpoint-jpa", decision.checkpointId().orElseThrow());
        assertEquals(1L, decision.checkpointSequence().orElseThrow());
        assertEquals(1, decision.modelFactCount());
        assertEquals(1, decision.toolFactCount());
        assertEquals(runCount, runRepository.count());
        assertEquals(stepCount, stepRepository.count());
        assertEquals(toolCount, toolRepository.count());
        assertEquals(checkpointCount, checkpointRepository.count());
        assertEquals(
                AgentRunStatus.RUNNING,
                runRepository.findByRunId(RUN_ID).orElseThrow().getStatus()
        );
        assertEquals(
                ToolExecutionStatus.PLANNED,
                toolRepository.findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID)
                        .orElseThrow()
                        .getStatus()
        );
    }

    @Test
    void startedToolFromRealHistoryRequiresReconciliation() {
        AgentStepEntity step = stepRepository.findByStepId(STEP_ID).orElseThrow();
        step.setStatus(AgentStepStatus.RUNNING);
        step.setStartedAt(Instant.parse("2026-09-28T03:01:00Z"));
        stepRepository.saveAndFlush(step);
        ToolExecutionEntity tool = toolRepository
                .findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID)
                .orElseThrow();
        tool.setStatus(ToolExecutionStatus.STARTED);
        tool.setStartedAt(Instant.parse("2026-09-28T03:01:00Z"));
        toolRepository.saveAndFlush(tool);

        RecoveryDecision decision = evaluator.evaluate(evidenceReader.load(RUN_ID));

        assertEquals(RecoveryDisposition.REQUIRES_RECONCILIATION, decision.disposition());
        assertEquals(RecoveryReason.AMBIGUOUS_TOOL_EXECUTION, decision.primaryReason());
        assertEquals(List.of(TOOL_CALL_ID), decision.ambiguousTools().stream()
                .map(diagnostic -> diagnostic.toolCallId())
                .toList());
        assertEquals(
                ToolExecutionStatus.STARTED,
                toolRepository.findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID)
                        .orElseThrow()
                        .getStatus()
        );
    }

    private RecoveryCheckpoint checkpoint() {
        ToolCall call = new ToolCall(
                TOOL_CALL_ID,
                "knowledge_search",
                Map.of("query", "recovery")
        );
        return new RecoveryCheckpoint(
                "checkpoint-jpa",
                RUN_ID,
                1,
                1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(
                        AgentMessage.user("find it"),
                        AgentMessage.assistantToolCalls(List.of(call))
                ),
                List.of(),
                Set.of(TOOL_CALL_ID),
                new BudgetCheckpoint(
                        1, 0, 10, 5, 15,
                        Optional.of(new BigDecimal("0.001")), false
                ),
                Set.of(),
                SNAPSHOT_ID,
                Instant.parse("2026-09-28T03:00:00Z"),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }
}
