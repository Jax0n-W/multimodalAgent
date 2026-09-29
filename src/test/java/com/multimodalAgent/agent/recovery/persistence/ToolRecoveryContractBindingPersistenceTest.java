package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRecoveryCheckpointRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.RecoveryEvidence;
import com.multimodalAgent.agent.recovery.RecoveryToolEvidence;
import com.multimodalAgent.agent.recovery.ToolReconciliationSupport;
import com.multimodalAgent.agent.recovery.ToolRecoveryAssessment;
import com.multimodalAgent.agent.recovery.ToolRecoveryAssessmentReason;
import com.multimodalAgent.agent.recovery.ToolRecoveryCapabilityEvaluator;
import com.multimodalAgent.agent.recovery.ToolRecoveryClass;
import com.multimodalAgent.agent.recovery.ToolRecoveryContract;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractConflictException;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import com.multimodalAgent.agent.recovery.ToolReplaySemantics;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10-tool-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        JpaToolRecoveryContractBindingStore.class,
        JpaRecoveryCheckpointStore.class,
        JpaRecoveryEvidenceReader.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ToolRecoveryContractBindingPersistenceTest {

    private static final String RUN_ID = "tool-recovery-run";
    private static final String STEP_ID = "tool-recovery-step";
    private static final String TOOL_CALL_ID = "tool-recovery-call";
    private static final String TOOL_NAME = "create_ticket";

    @Autowired
    private AgentRecoveryCheckpointRepository checkpointRepository;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolRepository;

    @Autowired
    private JpaToolRecoveryContractBindingStore bindingStore;

    @Autowired
    private JpaRecoveryEvidenceReader evidenceReader;

    private final ToolRecoveryCapabilityEvaluator capabilityEvaluator =
            new ToolRecoveryCapabilityEvaluator();

    @BeforeEach
    void setUp() {
        checkpointRepository.deleteAll();
        toolRepository.deleteAll();
        stepRepository.deleteAll();
        runRepository.deleteAll();

        AgentRunEntity run = new AgentRunEntity(
                RUN_ID,
                "request-tool-recovery",
                9L,
                "session-tool-recovery",
                AgentRunStatus.RUNNING,
                AgentRunPhase.TOOL_RUNNING
        );
        run.setCurrentIteration(1);
        runRepository.saveAndFlush(run);
        stepRepository.saveAndFlush(new AgentStepEntity(
                STEP_ID,
                RUN_ID,
                1,
                1,
                AgentStepType.TOOL,
                AgentStepStatus.RUNNING
        ));
        toolRepository.saveAndFlush(new ToolExecutionEntity(
                "tool-recovery-execution",
                RUN_ID,
                STEP_ID,
                TOOL_CALL_ID,
                TOOL_NAME,
                ToolExecutionStatus.STARTED
        ));
    }

    @Test
    void sameBindingIsIdempotentAndDifferentBindingConflicts() {
        ToolRecoveryContractSnapshot original = contract(
                "v1",
                ToolReplaySemantics.IDEMPOTENT
        );
        bindingStore.bind(RUN_ID, TOOL_CALL_ID, original);
        long versionAfterFirstBinding = tool().getVersion();

        bindingStore.bind(RUN_ID, TOOL_CALL_ID, original);

        ToolExecutionEntity restored = tool();
        assertEquals(original.contractId(), restored.getRecoveryContractId());
        assertEquals(versionAfterFirstBinding, restored.getVersion());
        assertThrows(ToolRecoveryContractConflictException.class, () ->
                bindingStore.bind(
                        RUN_ID,
                        TOOL_CALL_ID,
                        contract("v2", ToolReplaySemantics.IDEMPOTENT)
                )
        );
        assertEquals(original.contractId(), tool().getRecoveryContractId());
    }

    @Test
    void historicalContractSurvivesCurrentDescriptorDrift() {
        ToolRecoveryContractSnapshot historical = contract(
                "v1",
                ToolReplaySemantics.IDEMPOTENT
        );
        bindingStore.bind(RUN_ID, TOOL_CALL_ID, historical);

        ToolDescriptor<String> currentDescriptor = new ToolDescriptor<>(
                TOOL_NAME,
                "Current non-replayable descriptor",
                String.class,
                ToolRisk.HIGH,
                false,
                false,
                true,
                new ToolRecoveryContract(
                        "v2",
                        ToolReplaySemantics.NON_REPLAYABLE,
                        ToolReconciliationSupport.UNSUPPORTED,
                        Optional.empty()
                )
        );
        RecoveryToolEvidence evidence = onlyTool(evidenceReader.load(RUN_ID));
        ToolRecoveryAssessment assessment = capabilityEvaluator.evaluate(evidence);

        assertEquals("v1", assessment.contractVersion().orElseThrow());
        assertEquals(ToolReplaySemantics.IDEMPOTENT,
                assessment.replaySemantics().orElseThrow());
        assertEquals(ToolRecoveryClass.IDEMPOTENT,
                assessment.recoveryClass().orElseThrow());
        assertEquals(ToolReplaySemantics.NON_REPLAYABLE,
                currentDescriptor.recoveryContract().replaySemantics());
        assertFalse(historical.contractId().equals(
                currentDescriptor.recoveryContract().snapshot(TOOL_NAME).contractId()
        ));
    }

    @Test
    void historicalNullBindingRemainsReadableAndUnavailable() {
        RecoveryToolEvidence evidence = onlyTool(evidenceReader.load(RUN_ID));
        ToolRecoveryAssessment assessment = capabilityEvaluator.evaluate(evidence);

        assertTrue(evidence.recoveryContract().isEmpty());
        assertFalse(assessment.contractAvailable());
        assertEquals(ToolRecoveryAssessmentReason.CONTRACT_UNAVAILABLE, assessment.reason());
        assertTrue(assessment.recoveryClass().isEmpty());
        assertFalse(tool().hasAnyRecoveryContractField());
    }

    private ToolRecoveryContractSnapshot contract(
            String version,
            ToolReplaySemantics replaySemantics
    ) {
        return new ToolRecoveryContract(
                version,
                replaySemantics,
                ToolReconciliationSupport.UNSUPPORTED,
                Optional.empty()
        ).snapshot(TOOL_NAME);
    }

    private ToolExecutionEntity tool() {
        return toolRepository.findByRunIdAndToolCallId(RUN_ID, TOOL_CALL_ID).orElseThrow();
    }

    private RecoveryToolEvidence onlyTool(RecoveryEvidence evidence) {
        assertTrue(evidence.issues().isEmpty());
        assertEquals(1, evidence.toolFacts().size());
        return evidence.toolFacts().get(0);
    }
}
