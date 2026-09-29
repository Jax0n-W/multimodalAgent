package com.multimodalAgent.agent.recovery;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolAmbiguityPlannerTest {

    private final ToolAmbiguityPlanner planner = new ToolAmbiguityPlanner();

    @Test
    void plansEveryContractCapabilityWithoutExecutingIt() {
        assertPlan(
                contract(ToolReplaySemantics.REPLAY_SAFE, false),
                ToolAmbiguityAction.REPLAY_DEFERRED
        );
        assertPlan(
                contract(ToolReplaySemantics.IDEMPOTENT, false),
                ToolAmbiguityAction.IDEMPOTENT_RETRY_DEFERRED
        );
        assertPlan(
                contract(ToolReplaySemantics.NON_REPLAYABLE, false),
                ToolAmbiguityAction.MANUAL_INTERVENTION
        );

        ToolAmbiguityPlan reconcilable = planner.plan(evidence(Optional.of(
                contract(ToolReplaySemantics.NON_REPLAYABLE, true)
        )));
        assertEquals(ToolAmbiguityAction.RECONCILE, reconcilable.action());
        assertEquals("lookup-v1", reconcilable.reconciliationStrategyId().orElseThrow());

        ToolAmbiguityPlan unavailable = planner.plan(evidence(Optional.empty()));
        assertEquals(ToolAmbiguityAction.CONTRACT_UNAVAILABLE, unavailable.action());
        assertTrue(unavailable.reconciliationStrategyId().isEmpty());
    }

    private void assertPlan(
            ToolRecoveryContractSnapshot contract,
            ToolAmbiguityAction expected
    ) {
        ToolAmbiguityPlan plan = planner.plan(evidence(Optional.of(contract)));
        assertEquals(expected, plan.action());
        assertTrue(plan.reconciliationStrategyId().isEmpty());
    }

    private ToolRecoveryContractSnapshot contract(
            ToolReplaySemantics replay,
            boolean reconciliation
    ) {
        return new ToolRecoveryContract(
                "v1",
                replay,
                reconciliation
                        ? ToolReconciliationSupport.SUPPORTED
                        : ToolReconciliationSupport.UNSUPPORTED,
                reconciliation ? Optional.of("lookup-v1") : Optional.empty()
        ).snapshot("tool");
    }

    private RecoveryToolEvidence evidence(
            Optional<ToolRecoveryContractSnapshot> contract
    ) {
        return new RecoveryToolEvidence(
                "execution",
                "step",
                1,
                1,
                "call",
                "tool",
                RecoveryToolStatus.UNKNOWN,
                true,
                contract
        );
    }
}
