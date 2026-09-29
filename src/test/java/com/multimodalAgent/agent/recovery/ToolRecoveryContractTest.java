package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ToolRecoveryContractTest {

    @Test
    void descriptorFlagsDeriveOrthogonalRecoveryCapabilities() {
        ToolDescriptor<String> readOnly = descriptor(true, true);
        ToolDescriptor<String> idempotent = descriptor(false, true);
        ToolDescriptor<String> nonReplayable = descriptor(false, false);
        ToolRecoveryContract reconcilableContract = new ToolRecoveryContract(
                "v1",
                ToolReplaySemantics.NON_REPLAYABLE,
                ToolReconciliationSupport.SUPPORTED,
                Optional.of("external-lookup-v1")
        );
        ToolDescriptor<String> reconcilable = descriptor(
                false,
                false,
                reconcilableContract
        );

        assertEquals(
                ToolRecoveryClass.REPLAY_SAFE,
                readOnly.recoveryContract().snapshot(readOnly.name()).recoveryClass()
        );
        assertEquals(
                ToolRecoveryClass.IDEMPOTENT,
                idempotent.recoveryContract().snapshot(idempotent.name()).recoveryClass()
        );
        assertEquals(
                ToolRecoveryClass.RECONCILABLE,
                reconcilable.recoveryContract().snapshot(reconcilable.name()).recoveryClass()
        );
        assertEquals(
                ToolRecoveryClass.NON_REPLAYABLE,
                nonReplayable.recoveryContract().snapshot(nonReplayable.name()).recoveryClass()
        );
    }

    @Test
    void descriptorRejectsReplaySemanticsContradictingExistingFlags() {
        assertThrows(IllegalArgumentException.class, () -> descriptor(
                true,
                true,
                contract(ToolReplaySemantics.NON_REPLAYABLE)
        ));
        assertThrows(IllegalArgumentException.class, () -> descriptor(
                false,
                true,
                contract(ToolReplaySemantics.REPLAY_SAFE)
        ));
        assertThrows(IllegalArgumentException.class, () -> descriptor(
                false,
                false,
                contract(ToolReplaySemantics.IDEMPOTENT)
        ));
    }

    @Test
    void reconciliationStrategyIdentityIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> new ToolRecoveryContract(
                "v1",
                ToolReplaySemantics.NON_REPLAYABLE,
                ToolReconciliationSupport.SUPPORTED,
                Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new ToolRecoveryContract(
                "v1",
                ToolReplaySemantics.NON_REPLAYABLE,
                ToolReconciliationSupport.UNSUPPORTED,
                Optional.of("unexpected")
        ));
    }

    @Test
    void contractIdentityIsDeterministicAndCoversEveryRecoveryField() {
        ToolRecoveryContract base = contract(ToolReplaySemantics.NON_REPLAYABLE);
        ToolRecoveryContractSnapshot first = base.snapshot("calendar_create");
        ToolRecoveryContractSnapshot second = base.snapshot("calendar_create");

        assertEquals(first.contractId(), second.contractId());
        assertNotEquals(
                first.contractId(),
                new ToolRecoveryContract(
                        "v2",
                        ToolReplaySemantics.NON_REPLAYABLE,
                        ToolReconciliationSupport.UNSUPPORTED,
                        Optional.empty()
                ).snapshot("calendar_create").contractId()
        );
        assertNotEquals(
                first.contractId(),
                contract(ToolReplaySemantics.IDEMPOTENT)
                        .snapshot("calendar_create")
                        .contractId()
        );
        ToolRecoveryContract supported = new ToolRecoveryContract(
                "v1",
                ToolReplaySemantics.NON_REPLAYABLE,
                ToolReconciliationSupport.SUPPORTED,
                Optional.of("calendar-lookup-v1")
        );
        assertNotEquals(
                first.contractId(),
                supported.snapshot("calendar_create").contractId()
        );
        assertNotEquals(
                supported.snapshot("calendar_create").contractId(),
                new ToolRecoveryContract(
                        "v1",
                        ToolReplaySemantics.NON_REPLAYABLE,
                        ToolReconciliationSupport.SUPPORTED,
                        Optional.of("calendar-lookup-v2")
                ).snapshot("calendar_create").contractId()
        );
        assertNotEquals(
                first.contractId(),
                base.snapshot("different_tool").contractId()
        );
    }

    @Test
    void capabilityAssessmentPreservesMultipleCapabilitiesAndMissingContract() {
        ToolRecoveryContractSnapshot snapshot = new ToolRecoveryContract(
                "v7",
                ToolReplaySemantics.IDEMPOTENT,
                ToolReconciliationSupport.SUPPORTED,
                Optional.of("ticket-lookup-v3")
        ).snapshot("create_ticket");
        RecoveryToolEvidence available = evidence("create_ticket", Optional.of(snapshot));
        RecoveryToolEvidence missing = evidence("legacy_tool", Optional.empty());
        ToolRecoveryCapabilityEvaluator evaluator = new ToolRecoveryCapabilityEvaluator();

        ToolRecoveryAssessment assessment = evaluator.evaluate(available);
        assertTrue(assessment.contractAvailable());
        assertEquals(ToolRecoveryClass.IDEMPOTENT, assessment.recoveryClass().orElseThrow());
        assertEquals(
                ToolReconciliationSupport.SUPPORTED,
                assessment.reconciliationSupport().orElseThrow()
        );
        assertEquals("ticket-lookup-v3", assessment.reconciliationStrategyId().orElseThrow());

        ToolRecoveryAssessment unavailable = evaluator.evaluate(missing);
        assertEquals(ToolRecoveryAssessmentReason.CONTRACT_UNAVAILABLE, unavailable.reason());
        assertTrue(unavailable.contractId().isEmpty());
        assertTrue(unavailable.recoveryClass().isEmpty());
    }

    @Test
    void knowledgeSearchProductionDescriptorIsReplaySafeWithoutReconciliation() {
        ToolDescriptor<?> descriptor = new KnowledgeSearchTool(mock(KnowledgeService.class))
                .descriptor();

        assertEquals(ToolReplaySemantics.REPLAY_SAFE,
                descriptor.recoveryContract().replaySemantics());
        assertEquals(ToolReconciliationSupport.UNSUPPORTED,
                descriptor.recoveryContract().reconciliationSupport());
        assertEquals(ToolRecoveryClass.REPLAY_SAFE,
                descriptor.recoveryContract().snapshot(descriptor.name()).recoveryClass());
    }

    private ToolRecoveryContract contract(ToolReplaySemantics replaySemantics) {
        return new ToolRecoveryContract(
                "v1",
                replaySemantics,
                ToolReconciliationSupport.UNSUPPORTED,
                Optional.empty()
        );
    }

    private ToolDescriptor<String> descriptor(boolean readOnly, boolean idempotent) {
        return new ToolDescriptor<>(
                "test_tool_" + readOnly + "_" + idempotent,
                "Test tool",
                String.class,
                ToolRisk.LOW,
                readOnly,
                idempotent,
                false
        );
    }

    private ToolDescriptor<String> descriptor(
            boolean readOnly,
            boolean idempotent,
            ToolRecoveryContract contract
    ) {
        return new ToolDescriptor<>(
                "test_tool_" + readOnly + "_" + idempotent,
                "Test tool",
                String.class,
                ToolRisk.LOW,
                readOnly,
                idempotent,
                false,
                contract
        );
    }

    private RecoveryToolEvidence evidence(
            String toolName,
            Optional<ToolRecoveryContractSnapshot> contract
    ) {
        return new RecoveryToolEvidence(
                "execution-1",
                "step-1",
                1,
                1,
                "call-1",
                toolName,
                RecoveryToolStatus.STARTED,
                true,
                contract
        );
    }
}
