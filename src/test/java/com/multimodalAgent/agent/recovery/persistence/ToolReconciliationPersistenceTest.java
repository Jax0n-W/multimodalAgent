package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.integration.JpaExecutionHistoryStore;
import com.multimodalAgent.agent.persistence.integration.JpaAgentContextSnapshotStore;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRecoveryCheckpointRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.persistence.repository.ToolReconciliationAttemptRepository;
import com.multimodalAgent.agent.recovery.RecoveryAuthorityGuard;
import com.multimodalAgent.agent.recovery.ToolAmbiguityPlanner;
import com.multimodalAgent.agent.recovery.ToolReconciler;
import com.multimodalAgent.agent.recovery.ToolReconcilerRegistry;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttempt;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttemptStatus;
import com.multimodalAgent.agent.recovery.ToolReconciliationContext;
import com.multimodalAgent.agent.recovery.ToolReconciliationCoordinator;
import com.multimodalAgent.agent.recovery.ToolReconciliationOutcome;
import com.multimodalAgent.agent.recovery.ToolReconciliationResolution;
import com.multimodalAgent.agent.recovery.ToolReconciliationResolutionStatus;
import com.multimodalAgent.agent.recovery.ToolReconciliationResult;
import com.multimodalAgent.agent.recovery.ToolReconciliationStart;
import com.multimodalAgent.agent.recovery.ToolReconciliationStartDisposition;
import com.multimodalAgent.agent.recovery.ToolReconciliationSupport;
import com.multimodalAgent.agent.recovery.ToolRecoveryContract;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import com.multimodalAgent.agent.recovery.ToolReplaySemantics;
import com.multimodalAgent.agent.recovery.ToolUnknownMaterializationResult;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.ToolFailedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10-reconciliation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaToolRecoveryStateStore.class,
        JpaToolReconciliationAttemptStore.class,
        JpaRecoveryCheckpointStore.class,
        JpaRecoveryEvidenceReader.class,
        JpaAgentContextSnapshotStore.class,
        JpaExecutionHistoryStore.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ToolReconciliationPersistenceTest {

    private static final String RUN_ID = "reconciliation-run";
    private static final String TOOL_CALL_ID = "reconciliation-call";
    private static final String TOOL_NAME = "charge_card";
    private static final String EXECUTION_ID = "reconciliation-execution";
    private static final String STRATEGY_ID = "payment-lookup-v1";
    private static final String STEP_ID = stableId("tool-step", RUN_ID, TOOL_CALL_ID);
    private static final ToolRecoveryContractSnapshot CONTRACT = new ToolRecoveryContract(
            "v1",
            ToolReplaySemantics.NON_REPLAYABLE,
            ToolReconciliationSupport.SUPPORTED,
            Optional.of(STRATEGY_ID)
    ).snapshot(TOOL_NAME);

    @Autowired
    private AgentRecoveryCheckpointRepository checkpointRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolRepository;

    @Autowired
    private ToolReconciliationAttemptRepository attemptRepository;

    @Autowired
    private JpaToolRecoveryStateStore stateStore;

    @Autowired
    private JpaToolReconciliationAttemptStore attemptStore;

    @Autowired
    private JpaRecoveryEvidenceReader evidenceReader;

    @Autowired
    private JpaExecutionHistoryStore historyStore;

    @BeforeEach
    void setUp() {
        checkpointRepository.deleteAll();
        attemptRepository.deleteAll();
        toolRepository.deleteAll();
        stepRepository.deleteAll();
        runRepository.deleteAll();

        AgentRunEntity run = new AgentRunEntity(
                RUN_ID,
                "request-reconciliation",
                17L,
                "session-reconciliation",
                AgentRunStatus.RUNNING,
                AgentRunPhase.TOOL_RUNNING
        );
        run.setCurrentIteration(1);
        runRepository.saveAndFlush(run);
        AgentStepEntity step = new AgentStepEntity(
                STEP_ID,
                RUN_ID,
                1,
                1,
                AgentStepType.TOOL,
                AgentStepStatus.RUNNING
        );
        step.setStartedAt(Instant.now());
        stepRepository.saveAndFlush(step);
        ToolExecutionEntity execution = new ToolExecutionEntity(
                EXECUTION_ID,
                RUN_ID,
                STEP_ID,
                TOOL_CALL_ID,
                TOOL_NAME,
                ToolExecutionStatus.STARTED
        );
        execution.setStartedAt(Instant.now());
        execution.bindRecoveryContract(CONTRACT);
        toolRepository.saveAndFlush(execution);
    }

    @Test
    void unknownMaterializationIsStrictAndDoesNotChangeRunOrStep() {
        assertEquals(
                ToolUnknownMaterializationResult.MATERIALIZED_UNKNOWN,
                stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(
                ToolUnknownMaterializationResult.ALREADY_UNKNOWN,
                stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID)
        );
        assertEquals(ToolExecutionStatus.UNKNOWN, tool().getStatus());
        assertEquals(AgentRunStatus.RUNNING, runRepository.findByRunId(RUN_ID).orElseThrow()
                .getStatus());
        assertEquals(AgentStepStatus.RUNNING, stepRepository.findByStepId(STEP_ID).orElseThrow()
                .getStatus());

        setToolStatus(ToolExecutionStatus.PLANNED);
        assertEquals(
                ToolUnknownMaterializationResult.NOT_AMBIGUOUS,
                stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID)
        );
        for (ToolExecutionStatus terminal : List.of(
                ToolExecutionStatus.SUCCEEDED,
                ToolExecutionStatus.FAILED,
                ToolExecutionStatus.BLOCKED,
                ToolExecutionStatus.CANCELLED
        )) {
            setToolStatus(terminal);
            assertEquals(
                    ToolUnknownMaterializationResult.ALREADY_TERMINAL,
                    stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID)
            );
            assertEquals(terminal, tool().getStatus());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ToolReconciliationOutcome.class, names = {
            "CONFIRMED_APPLIED", "CONFIRMED_NOT_APPLIED"
    })
    void externalObservationRunsAfterStartedCommitAndLeavesToolUnknown(
            ToolReconciliationOutcome outcome
    ) {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciler reconciler = new ToolReconciler() {
            @Override
            public String strategyId() {
                return STRATEGY_ID;
            }

            @Override
            public ToolReconciliationResult reconcile(ToolReconciliationContext context) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(attemptRepository.findByReconciliationId(context.reconciliationId())
                        .isPresent());
                assertEquals(
                        ToolReconciliationAttemptStatus.STARTED,
                        attemptRepository.findByReconciliationId(context.reconciliationId())
                                .orElseThrow()
                                .getStatus()
                );
                assertEquals(ToolExecutionStatus.UNKNOWN, tool().getStatus());
                return new ToolReconciliationResult(
                        outcome,
                        Optional.of("external-17"),
                        Optional.of("observed external state")
                );
            }
        };
        ToolReconciliationCoordinator coordinator = coordinator(reconciler);

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.COMPLETED, resolution.status());
        assertEquals(outcome, resolution.attempt().orElseThrow().outcome().orElseThrow());
        assertEquals(ToolExecutionStatus.UNKNOWN, tool().getStatus());

        ToolReconciliationResolution reused = coordinator.resolve(RUN_ID, TOOL_CALL_ID);
        assertEquals(ToolReconciliationResolutionStatus.REUSED, reused.status());
    }

    @Test
    void unresolvedAndFailedAttemptsMayRetryWithStrictlyIncreasingSequence() {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciliationStart first = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        attemptStore.complete(
                first.attempt().orElseThrow().reconciliationId(),
                ToolReconciliationResult.of(ToolReconciliationOutcome.UNRESOLVED)
        );
        ToolReconciliationStart second = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        attemptStore.fail(
                second.attempt().orElseThrow().reconciliationId(),
                "LOOKUP_FAILED",
                "temporary lookup failure"
        );
        ToolReconciliationStart third = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);

        assertEquals(1L, first.attempt().orElseThrow().attemptNo());
        assertEquals(2L, second.attempt().orElseThrow().attemptNo());
        assertEquals(3L, third.attempt().orElseThrow().attemptNo());
        assertEquals(ToolExecutionStatus.UNKNOWN, tool().getStatus());
    }

    @Test
    void existingStartedAttemptRemainsInProgressWithoutCreatingAnotherAttempt() {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciliationStart started = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(new ToolReconciler() {
            @Override
            public String strategyId() {
                return STRATEGY_ID;
            }

            @Override
            public ToolReconciliationResult reconcile(ToolReconciliationContext context) {
                calls.incrementAndGet();
                return ToolReconciliationResult.of(
                        ToolReconciliationOutcome.CONFIRMED_APPLIED
                );
            }
        });

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationResolutionStatus.IN_PROGRESS, resolution.status());
        assertEquals(started.attempt().orElseThrow().reconciliationId(),
                resolution.attempt().orElseThrow().reconciliationId());
        assertEquals(0, calls.get());
        assertEquals(1, attemptRepository.count());
        assertEquals(ToolExecutionStatus.UNKNOWN, tool().getStatus());
    }

    @Test
    void concurrentRetryAllocationCreatesOnlyOneNextAttempt() throws Exception {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciliationStart first = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        attemptStore.complete(
                first.attempt().orElseThrow().reconciliationId(),
                ToolReconciliationResult.of(ToolReconciliationOutcome.UNRESOLVED)
        );
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<ToolReconciliationStart> task = () -> {
                ready.countDown();
                go.await();
                return attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
            };
            Future<ToolReconciliationStart> left = executor.submit(task);
            Future<ToolReconciliationStart> right = executor.submit(task);
            ready.await();
            go.countDown();
            List<ToolReconciliationStartDisposition> dispositions = List.of(
                    left.get().disposition(),
                    right.get().disposition()
            );

            assertTrue(dispositions.contains(ToolReconciliationStartDisposition.STARTED));
            assertTrue(dispositions.contains(
                    ToolReconciliationStartDisposition.ALREADY_IN_PROGRESS
            ));
            List<Long> attempts = attemptRepository
                    .findByToolExecutionIdOrderByAttemptNoDesc(EXECUTION_ID)
                    .stream()
                    .map(entity -> entity.getAttemptNo())
                    .toList();
            assertEquals(List.of(2L, 1L), attempts);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ToolExecutionStatus.class, names = {"SUCCEEDED", "FAILED"})
    void lateOriginalTerminalFactSupersedesRunningReconciliation(
            ToolExecutionStatus terminal
    ) {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciliationStart start = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        recordTerminal(terminal);

        ToolReconciliationAttempt completed = attemptStore.complete(
                start.attempt().orElseThrow().reconciliationId(),
                ToolReconciliationResult.of(ToolReconciliationOutcome.CONFIRMED_APPLIED)
        );

        assertEquals(ToolReconciliationAttemptStatus.SUPERSEDED, completed.status());
        assertEquals(terminal, tool().getStatus());
        assertEquals(AgentRunStatus.RUNNING,
                runRepository.findByRunId(RUN_ID).orElseThrow().getStatus());
        assertEquals(1, attemptRepository.count());
    }

    @ParameterizedTest
    @EnumSource(value = ToolExecutionStatus.class, names = {"SUCCEEDED", "FAILED"})
    void lateTerminalOutranksHistoricalDecisiveObservationOnFutureResolve(
            ToolExecutionStatus terminal
    ) {
        stateStore.materializeUnknown(RUN_ID, TOOL_CALL_ID);
        ToolReconciliationStart start = attemptStore.start(RUN_ID, TOOL_CALL_ID, CONTRACT);
        ToolReconciliationAttempt decisive = attemptStore.complete(
                start.attempt().orElseThrow().reconciliationId(),
                ToolReconciliationResult.of(
                        ToolReconciliationOutcome.CONFIRMED_NOT_APPLIED
                )
        );
        recordTerminal(terminal);
        AtomicInteger calls = new AtomicInteger();
        ToolReconciliationCoordinator coordinator = coordinator(new ToolReconciler() {
            @Override
            public String strategyId() {
                return STRATEGY_ID;
            }

            @Override
            public ToolReconciliationResult reconcile(ToolReconciliationContext context) {
                calls.incrementAndGet();
                return ToolReconciliationResult.of(ToolReconciliationOutcome.UNRESOLVED);
            }
        });

        ToolReconciliationResolution resolution = coordinator.resolve(RUN_ID, TOOL_CALL_ID);

        assertEquals(ToolReconciliationAttemptStatus.COMPLETED, decisive.status());
        assertEquals(ToolReconciliationOutcome.CONFIRMED_NOT_APPLIED,
                decisive.outcome().orElseThrow());
        assertEquals(ToolReconciliationResolutionStatus.NOT_AMBIGUOUS, resolution.status());
        assertTrue(resolution.plan().isEmpty());
        assertTrue(resolution.attempt().isEmpty());
        assertEquals(0, calls.get());
        assertEquals(terminal, tool().getStatus());
        assertEquals(1, attemptRepository.count());
    }

    private ToolReconciliationCoordinator coordinator(ToolReconciler reconciler) {
        RecoveryAuthorityGuard authority = ignored -> {
        };
        return new ToolReconciliationCoordinator(
                authority,
                stateStore,
                evidenceReader,
                new ToolAmbiguityPlanner(),
                new ToolReconcilerRegistry(List.of(reconciler)),
                attemptStore
        );
    }

    private void setToolStatus(ToolExecutionStatus status) {
        ToolExecutionEntity execution = tool();
        execution.setStatus(status);
        toolRepository.saveAndFlush(execution);
    }

    private void recordTerminal(ToolExecutionStatus terminal) {
        AgentEventMetadata metadata = new AgentEventMetadata(
                "event-" + terminal,
                RUN_ID,
                1,
                Instant.now(),
                1
        );
        if (terminal == ToolExecutionStatus.SUCCEEDED) {
            historyStore.record(new ToolSucceededEvent(metadata, TOOL_CALL_ID, TOOL_NAME));
            return;
        }
        historyStore.record(new ToolFailedEvent(
                metadata,
                TOOL_CALL_ID,
                TOOL_NAME,
                ToolErrorCode.EXECUTION_FAILED
        ));
    }

    private ToolExecutionEntity tool() {
        return toolRepository.findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID).orElseThrow();
    }

    private static String stableId(String kind, String runId, String correlationId) {
        return UUID.nameUUIDFromBytes(
                (kind + "\u0000" + runId + "\u0000" + correlationId)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
