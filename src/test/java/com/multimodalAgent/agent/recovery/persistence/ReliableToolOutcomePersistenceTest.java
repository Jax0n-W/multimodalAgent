package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionOutcomeRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.ReliableToolOutcome;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeConflictException;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterialization;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterializationStatus;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterializer;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10-reliable-outcome;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaReliableToolOutcomeStore.class,
        JpaReliableToolOutcomeMaterializationStore.class
})
class ReliableToolOutcomePersistenceTest {

    private static final String RUN_ID = "reliable-run";
    private static final String STEP_ID = "reliable-step";
    private static final String EXECUTION_ID = "reliable-execution";
    private static final String TOOL_CALL_ID = "reliable-call";
    private static final String TOOL_NAME = "charge_card";

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository executionRepository;

    @Autowired
    private ToolExecutionOutcomeRepository outcomeRepository;

    @Autowired
    private JpaReliableToolOutcomeStore outcomeStore;

    @Autowired
    private JpaReliableToolOutcomeMaterializationStore materializationStore;

    @BeforeEach
    void setUp() {
        outcomeRepository.deleteAll();
        executionRepository.deleteAll();
        stepRepository.deleteAll();
        runRepository.deleteAll();

        runRepository.saveAndFlush(new AgentRunEntity(
                RUN_ID,
                "request-reliable",
                31L,
                "session-reliable",
                AgentRunStatus.RUNNING,
                AgentRunPhase.TOOL_RUNNING
        ));
        AgentStepEntity step = new AgentStepEntity(
                STEP_ID,
                RUN_ID,
                1,
                1,
                AgentStepType.TOOL,
                AgentStepStatus.RUNNING
        );
        step.setStartedAt(Instant.parse("2026-09-29T03:00:00Z"));
        stepRepository.saveAndFlush(step);
        ToolExecutionEntity execution = new ToolExecutionEntity(
                EXECUTION_ID,
                RUN_ID,
                STEP_ID,
                TOOL_CALL_ID,
                TOOL_NAME,
                ToolExecutionStatus.STARTED
        );
        execution.setStartedAt(Instant.parse("2026-09-29T03:00:00Z"));
        executionRepository.saveAndFlush(execution);
    }

    @Test
    void crashAfterRecordingLeavesExactOutcomeRecoverableAndExecutionStarted() {
        String payload = "{\"receipt\":\"r-17\",\"amount\":42}";

        outcomeStore.recordSuccess(RUN_ID, TOOL_CALL_ID, TOOL_NAME, payload);

        assertEquals(ToolExecutionStatus.STARTED, execution().getStatus());
        ReliableToolOutcome restored = outcomeStore
                .findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID)
                .orElseThrow();
        assertEquals(payload, restored.modelVisibleResult());
        assertEquals(ReliableToolOutcome.hash(payload), restored.payloadHash());
        assertFalse(restored.toString().contains("r-17"));
        ToolResult reconstructed = ToolResult.success(
                restored.modelVisibleResult(),
                ToolPolicyDecision.allow()
        );
        assertEquals(payload, reconstructed.messageForModel());
    }

    @Test
    void sameExactOutcomeIsIdempotentAndDifferentOutcomeConflicts() {
        outcomeStore.recordSuccess(RUN_ID, TOOL_CALL_ID, TOOL_NAME, "exact");
        outcomeStore.recordSuccess(RUN_ID, TOOL_CALL_ID, TOOL_NAME, "exact");

        assertEquals(1, outcomeRepository.count());
        assertThrows(
                ReliableToolOutcomeConflictException.class,
                () -> outcomeStore.recordSuccess(
                        RUN_ID,
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        "different"
                )
        );
        assertEquals("exact", outcomeStore.findByExecutionId(EXECUTION_ID)
                .orElseThrow().modelVisibleResult());
    }

    @Test
    void exactOutcomeMaterializesStepAndExecutionWithoutChangingRun() {
        outcomeStore.recordSuccess(RUN_ID, TOOL_CALL_ID, TOOL_NAME, "exact");
        ReliableToolOutcomeMaterializer materializer = new ReliableToolOutcomeMaterializer(
                runId -> assertEquals(RUN_ID, runId),
                materializationStore
        );

        ReliableToolOutcomeMaterialization first =
                materializer.materialize(RUN_ID, TOOL_CALL_ID);
        ReliableToolOutcomeMaterialization repeated =
                materializer.materialize(RUN_ID, TOOL_CALL_ID);

        assertEquals(ReliableToolOutcomeMaterializationStatus.MATERIALIZED, first.status());
        assertEquals(
                ReliableToolOutcomeMaterializationStatus.ALREADY_MATERIALIZED,
                repeated.status()
        );
        assertEquals(ToolExecutionStatus.SUCCEEDED, execution().getStatus());
        assertEquals(AgentStepStatus.SUCCEEDED,
                stepRepository.findByStepId(STEP_ID).orElseThrow().getStatus());
        AgentRunEntity run = runRepository.findByRunId(RUN_ID).orElseThrow();
        assertEquals(AgentRunStatus.RUNNING, run.getStatus());
        assertEquals(AgentRunPhase.TOOL_RUNNING, run.getPhase());
    }

    @Test
    void confirmedAppliedOrHistoricalSuccessWithoutExactOutcomeFailsClosed() {
        ToolExecutionEntity unknown = execution();
        unknown.setStatus(ToolExecutionStatus.UNKNOWN);
        executionRepository.saveAndFlush(unknown);

        assertEquals(
                ReliableToolOutcomeMaterializationStatus.RELIABLE_OUTCOME_UNAVAILABLE,
                materializationStore.materialize(RUN_ID, TOOL_CALL_ID).status()
        );

        ToolExecutionEntity historical = execution();
        historical.setStatus(ToolExecutionStatus.SUCCEEDED);
        executionRepository.saveAndFlush(historical);
        AgentStepEntity historicalStep = stepRepository.findByStepId(STEP_ID).orElseThrow();
        historicalStep.setStatus(AgentStepStatus.SUCCEEDED);
        stepRepository.saveAndFlush(historicalStep);
        assertEquals(
                ReliableToolOutcomeMaterializationStatus.RELIABLE_OUTCOME_UNAVAILABLE,
                materializationStore.materialize(RUN_ID, TOOL_CALL_ID).status()
        );
    }

    @Test
    void conflictingTerminalTruthIsNeverOverwritten() {
        outcomeStore.recordSuccess(RUN_ID, TOOL_CALL_ID, TOOL_NAME, "exact");
        ToolExecutionEntity failed = execution();
        failed.setStatus(ToolExecutionStatus.FAILED);
        executionRepository.saveAndFlush(failed);
        AgentStepEntity failedStep = stepRepository.findByStepId(STEP_ID).orElseThrow();
        failedStep.setStatus(AgentStepStatus.FAILED);
        stepRepository.saveAndFlush(failedStep);

        assertEquals(
                ReliableToolOutcomeMaterializationStatus.CONFLICTING_TERMINAL,
                materializationStore.materialize(RUN_ID, TOOL_CALL_ID).status()
        );
        assertEquals(ToolExecutionStatus.FAILED, execution().getStatus());
        assertTrue(outcomeStore.findByExecutionId(EXECUTION_ID).isPresent());
    }

    private ToolExecutionEntity execution() {
        return executionRepository.findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID)
                .orElseThrow();
    }
}
