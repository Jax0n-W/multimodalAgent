package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEligibilityEvaluatorTest {

    private static final String RUN_ID = "run-eligibility";
    private static final String SNAPSHOT_ID = "cfg-eligibility";
    private static final ToolCall CALL = new ToolCall("call-1", "knowledge_search", Map.of());

    private final RecoveryEligibilityEvaluator evaluator = new RecoveryEligibilityEvaluator();

    @Test
    void terminalTruthAlwaysWins() {
        for (RecoveryRunStatus status : List.of(
                RecoveryRunStatus.COMPLETED,
                RecoveryRunStatus.FAILED,
                RecoveryRunStatus.CANCELLED
        )) {
            RecoveryDecision decision = evaluator.evaluate(evidence(
                    run(status, 1),
                    Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                    List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                    List.of(tool(1, 2, RecoveryToolStatus.STARTED)),
                    List.of(RecoveryEvidenceIssue.CHECKPOINT_UNREADABLE)
            ));

            assertDecision(
                    decision,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.RUN_TERMINAL
            );
        }
    }

    @Test
    void createdRunIsNotCrashRecovery() {
        assertDecision(
                evaluator.evaluate(evidence(
                        run(RecoveryRunStatus.CREATED, 0),
                        Optional.empty(),
                        List.of(),
                        List.of(),
                        List.of()
                )),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.RUN_NOT_STARTED
        );
    }

    @Test
    void missingRunFailsClosed() {
        assertDecision(
                evaluator.evaluate(RecoveryEvidence.missingRun(RUN_ID)),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.RUN_NOT_FOUND
        );
    }

    @Test
    void runningRunRequiresCheckpoint() {
        assertDecision(
                evaluator.evaluate(evidence(
                        run(RecoveryRunStatus.RUNNING, 1),
                        Optional.empty(),
                        List.of(model(1, 1, RecoveryModelStatus.RUNNING)),
                        List.of(),
                        List.of()
                )),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.MISSING_CHECKPOINT
        );
    }

    @Test
    void waitingApprovalRequiresMatchingCheckpointBoundary() {
        RecoveryEvidence valid = evidence(
                run(RecoveryRunStatus.WAITING_APPROVAL, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.WAITING_APPROVAL)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(),
                List.of()
        );
        assertDecision(
                evaluator.evaluate(valid),
                RecoveryDisposition.MANUAL_INTERVENTION,
                RecoveryReason.AWAITING_APPROVAL
        );

        RecoveryEvidence inadequate = evidence(
                run(RecoveryRunStatus.WAITING_APPROVAL, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(),
                List.of()
        );
        assertDecision(
                evaluator.evaluate(inadequate),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.MISSING_APPROVAL_CHECKPOINT
        );

        RecoveryEvidence missing = evidence(
                run(RecoveryRunStatus.WAITING_APPROVAL, 1),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of()
        );
        assertDecision(
                evaluator.evaluate(missing),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.MISSING_APPROVAL_CHECKPOINT
        );
    }

    @Test
    void waitingApprovalRejectsStartedAndUnknownToolHistory() {
        for (RecoveryToolStatus status : List.of(
                RecoveryToolStatus.STARTED,
                RecoveryToolStatus.UNKNOWN
        )) {
            RecoveryDecision decision = evaluator.evaluate(evidence(
                    run(RecoveryRunStatus.WAITING_APPROVAL, 1),
                    Optional.of(toolCallCheckpoint(
                            1,
                            RecoveryCheckpointBoundary.WAITING_APPROVAL
                    )),
                    List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                    List.of(tool(1, 2, status)),
                    List.of()
            ));

            assertDecision(
                    decision,
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.INCONSISTENT_TOOL_HISTORY
            );
        }
    }

    @Test
    void waitingApprovalAllowsRepresentedPlannedTool() {
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.WAITING_APPROVAL, 1),
                Optional.of(toolCallCheckpoint(
                        1,
                        RecoveryCheckpointBoundary.WAITING_APPROVAL
                )),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(tool(1, 2, RecoveryToolStatus.PLANNED)),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.MANUAL_INTERVENTION,
                RecoveryReason.AWAITING_APPROVAL
        );
    }

    @Test
    void plannedToolRepresentedByCheckpointDoesNotBlockResume() {
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(tool(1, 2, RecoveryToolStatus.PLANNED)),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.SAFE_TO_RESUME,
                RecoveryReason.ELIGIBLE
        );
    }

    @Test
    void startedAndUnknownToolsRequireReconciliation() {
        for (RecoveryToolStatus status : List.of(
                RecoveryToolStatus.STARTED,
                RecoveryToolStatus.UNKNOWN
        )) {
            RecoveryDecision decision = evaluator.evaluate(evidence(
                    run(RecoveryRunStatus.RUNNING, 1),
                    Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                    List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                    List.of(tool(1, 2, status)),
                    List.of()
            ));

            assertDecision(
                    decision,
                    RecoveryDisposition.REQUIRES_RECONCILIATION,
                    RecoveryReason.AMBIGUOUS_TOOL_EXECUTION
            );
            assertEquals(
                    List.of(new RecoveryToolDiagnostic(CALL.id(), CALL.name(), status)),
                    decision.ambiguousTools()
            );
        }
    }

    @Test
    void confirmedToolOutcomeMustHaveExactCheckpointResult() {
        RecoveryEvidence covered = evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolResultCheckpoint(1)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(tool(1, 2, RecoveryToolStatus.SUCCEEDED)),
                List.of()
        );
        assertDecision(
                evaluator.evaluate(covered),
                RecoveryDisposition.SAFE_TO_RESUME,
                RecoveryReason.ELIGIBLE
        );

        for (RecoveryToolStatus status : List.of(
                RecoveryToolStatus.SUCCEEDED,
                RecoveryToolStatus.FAILED,
                RecoveryToolStatus.BLOCKED,
                RecoveryToolStatus.CANCELLED
        )) {
            RecoveryEvidence behind = evidence(
                    run(RecoveryRunStatus.RUNNING, 1),
                    Optional.of(toolCallCheckpoint(
                            1,
                            RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
                    )),
                    List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                    List.of(tool(1, 2, status)),
                    List.of()
            );
            assertDecision(
                    evaluator.evaluate(behind),
                    RecoveryDisposition.NOT_RESUMABLE,
                    RecoveryReason.CHECKPOINT_BEHIND_CONFIRMED_TOOL_FACT
            );
        }
    }

    @Test
    void checkpointToolResultContradictingStartedHistoryFailsClosed() {
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolResultCheckpoint(1)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(tool(1, 2, RecoveryToolStatus.STARTED)),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_TOOL_HISTORY
        );
    }

    @Test
    void plannedToolUnknownToCheckpointFailsClosed() {
        RecoveryToolEvidence unknownPlan = new RecoveryToolEvidence(
                "exec-2", "tool-step-2", 1, 2,
                "call-2", "other_tool", RecoveryToolStatus.PLANNED, true
        );
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(unknownPlan),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_TOOL_HISTORY
        );
    }

    @Test
    void confirmedModelOutcomeMustNotBeNewerThanCheckpoint() {
        RecoveryCheckpoint checkpoint = toolResultCheckpoint(1);
        List<RecoveryModelEvidence> models = List.of(
                model(1, 1, RecoveryModelStatus.SUCCEEDED),
                model(2, 3, RecoveryModelStatus.SUCCEEDED)
        );
        RecoveryDecision succeeded = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 2),
                Optional.of(checkpoint),
                models,
                List.of(tool(1, 2, RecoveryToolStatus.SUCCEEDED)),
                List.of()
        ));
        assertDecision(
                succeeded,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.CHECKPOINT_BEHIND_CONFIRMED_MODEL_FACT
        );

        RecoveryDecision failed = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 2),
                Optional.of(checkpoint),
                List.of(
                        model(1, 1, RecoveryModelStatus.SUCCEEDED),
                        model(2, 3, RecoveryModelStatus.FAILED)
                ),
                List.of(tool(1, 2, RecoveryToolStatus.SUCCEEDED)),
                List.of()
        ));
        assertDecision(
                failed,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.CHECKPOINT_BEHIND_CONFIRMED_MODEL_FACT
        );
    }

    @Test
    void inFlightModelIsNotToolReconciliation() {
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 2),
                Optional.of(toolResultCheckpoint(1)),
                List.of(
                        model(1, 1, RecoveryModelStatus.SUCCEEDED),
                        model(2, 3, RecoveryModelStatus.RUNNING)
                ),
                List.of(tool(1, 2, RecoveryToolStatus.SUCCEEDED)),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.IN_FLIGHT_MODEL_ATTEMPT
        );
    }

    @Test
    void runningModelAlreadyCoveredByCheckpointIsInconsistent() {
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.RUNNING)),
                List.of(),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_MODEL_HISTORY
        );
    }

    @Test
    void checkpointIterationAndSnapshotMustMatchRun() {
        RecoveryDecision futureCheckpoint = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolCallCheckpoint(2, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(),
                List.of()
        ));
        assertDecision(
                futureCheckpoint,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_CHECKPOINT_STATE
        );

        RecoveryCheckpoint original = toolCallCheckpoint(
                1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
        );
        RecoveryCheckpoint wrongSnapshot = new RecoveryCheckpoint(
                original.checkpointId(), original.runId(), original.sequence(),
                original.iteration(), original.boundary(), original.messages(),
                original.toolsUsed(), original.seenToolCallIds(), original.budgetUsage(),
                original.approvedToolCallIds(), "cfg-other", original.createdAt(),
                original.schemaVersion()
        );
        assertDecision(
                evaluator.evaluate(evidence(
                        run(RecoveryRunStatus.RUNNING, 1),
                        Optional.of(wrongSnapshot),
                        List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                        List.of(),
                        List.of()
                )),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_CHECKPOINT_STATE
        );
    }

    @Test
    void unreadableCheckpointAndBrokenToolLinkageFailClosed() {
        RecoveryEvidence unreadable = evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(RecoveryEvidenceIssue.CHECKPOINT_UNREADABLE)
        );
        assertDecision(
                evaluator.evaluate(unreadable),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_CHECKPOINT_STATE
        );

        RecoveryToolEvidence broken = new RecoveryToolEvidence(
                "exec-broken", "missing-step", 0, 0,
                CALL.id(), CALL.name(), RecoveryToolStatus.PLANNED, false
        );
        assertDecision(
                evaluator.evaluate(evidence(
                        run(RecoveryRunStatus.RUNNING, 1),
                        Optional.of(toolCallCheckpoint(
                                1,
                                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
                        )),
                        List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                        List.of(broken),
                        List.of(RecoveryEvidenceIssue.TOOL_STEP_MISSING)
                )),
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.INCONSISTENT_TOOL_HISTORY
        );
    }

    @Test
    void stopVersusLengthControlGapFailsClosed() {
        RecoveryCheckpoint checkpoint = plainModelCheckpoint(1);
        RecoveryDecision decision = evaluator.evaluate(evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(checkpoint),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(),
                List.of()
        ));

        assertDecision(
                decision,
                RecoveryDisposition.NOT_RESUMABLE,
                RecoveryReason.UNRESOLVED_MODEL_CONTROL_STATE
        );
    }

    @Test
    void sameEvidenceAlwaysProducesSameDecision() {
        RecoveryEvidence evidence = evidence(
                run(RecoveryRunStatus.RUNNING, 1),
                Optional.of(toolCallCheckpoint(1, RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME)),
                List.of(model(1, 1, RecoveryModelStatus.SUCCEEDED)),
                List.of(
                        tool(1, 3, RecoveryToolStatus.UNKNOWN),
                        new RecoveryToolEvidence(
                                "exec-0", "tool-step-0", 1, 2,
                                "call-0", "calendar", RecoveryToolStatus.STARTED, true
                        )
                ),
                List.of()
        );

        RecoveryDecision first = evaluator.evaluate(evidence);
        for (int index = 0; index < 20; index++) {
            assertEquals(first, evaluator.evaluate(evidence));
        }
        assertEquals(List.of("call-0", "call-1"), first.ambiguousTools().stream()
                .map(RecoveryToolDiagnostic::toolCallId)
                .toList());
    }

    private RecoveryEvidence evidence(
            RecoveryRunEvidence run,
            Optional<RecoveryCheckpoint> checkpoint,
            List<RecoveryModelEvidence> models,
            List<RecoveryToolEvidence> tools,
            List<RecoveryEvidenceIssue> issues
    ) {
        return new RecoveryEvidence(
                RUN_ID,
                Optional.of(run),
                checkpoint,
                models,
                tools,
                issues
        );
    }

    private RecoveryRunEvidence run(RecoveryRunStatus status, int currentIteration) {
        return new RecoveryRunEvidence(
                RUN_ID,
                status,
                currentIteration,
                Optional.of(SNAPSHOT_ID)
        );
    }

    private RecoveryModelEvidence model(
            int iteration,
            int stepIndex,
            RecoveryModelStatus status
    ) {
        return new RecoveryModelEvidence(
                "model-step-" + iteration,
                iteration,
                stepIndex,
                status
        );
    }

    private RecoveryToolEvidence tool(
            int iteration,
            int stepIndex,
            RecoveryToolStatus status
    ) {
        return new RecoveryToolEvidence(
                "exec-1",
                "tool-step-1",
                iteration,
                stepIndex,
                CALL.id(),
                CALL.name(),
                status,
                true
        );
    }

    private RecoveryCheckpoint toolCallCheckpoint(
            int iteration,
            RecoveryCheckpointBoundary boundary
    ) {
        return checkpoint(
                iteration,
                boundary,
                List.of(
                        AgentMessage.user("find it"),
                        AgentMessage.assistantToolCalls(List.of(CALL))
                ),
                Set.of(CALL.id())
        );
    }

    private RecoveryCheckpoint toolResultCheckpoint(int iteration) {
        return checkpoint(
                iteration,
                RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                List.of(
                        AgentMessage.user("find it"),
                        AgentMessage.assistantToolCalls(List.of(CALL)),
                        AgentMessage.toolResult(CALL.id(), CALL.name(), "found")
                ),
                Set.of(CALL.id())
        );
    }

    private RecoveryCheckpoint plainModelCheckpoint(int iteration) {
        return checkpoint(
                iteration,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(AgentMessage.user("hello"), AgentMessage.assistant("done")),
                Set.of()
        );
    }

    private RecoveryCheckpoint checkpoint(
            int iteration,
            RecoveryCheckpointBoundary boundary,
            List<AgentMessage> messages,
            Set<String> seenToolCallIds
    ) {
        return new RecoveryCheckpoint(
                "checkpoint-" + iteration + "-" + boundary,
                RUN_ID,
                1,
                iteration,
                boundary,
                messages,
                List.of(),
                new LinkedHashSet<>(seenToolCallIds),
                new BudgetCheckpoint(
                        1, 0, 10, 5, 15,
                        Optional.of(new BigDecimal("0.001")), false
                ),
                Set.of(),
                SNAPSHOT_ID,
                Instant.parse("2026-09-28T02:00:00Z"),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private void assertDecision(
            RecoveryDecision decision,
            RecoveryDisposition disposition,
            RecoveryReason reason
    ) {
        assertEquals(disposition, decision.disposition());
        assertEquals(reason, decision.primaryReason());
        assertEquals(RUN_ID, decision.runId());
        assertTrue(decision.modelFactCount() >= 0);
        assertTrue(decision.toolFactCount() >= 0);
    }
}
