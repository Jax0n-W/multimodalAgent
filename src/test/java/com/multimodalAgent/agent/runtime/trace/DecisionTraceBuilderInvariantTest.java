package com.multimodalAgent.agent.runtime.trace;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventMetadata;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.event.ModelFailedEvent;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.RunCompletedEvent;
import com.multimodalAgent.agent.runtime.event.RunStartedEvent;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.event.RunWaitingApprovalEvent;
import com.multimodalAgent.agent.runtime.event.ToolFailedEvent;
import com.multimodalAgent.agent.runtime.event.ToolPolicyEvaluatedEvent;
import com.multimodalAgent.agent.runtime.event.ToolRequestedEvent;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.event.ToolValidatedEvent;
import com.multimodalAgent.agent.runtime.event.ToolValidationFailedEvent;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionTraceBuilderInvariantTest {

    private static final String RUN_ID = "run-trace";
    private static final String CALL_ID = "call-1";
    private static final String TOOL_NAME = "knowledge_search";
    private final DecisionTraceBuilder builder = new DecisionTraceBuilder();

    @Test
    void shouldRejectSecondRunStarted() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                runStarted(RUN_ID, 2),
                runCompleted(RUN_ID, 3, 0)
        ));
    }

    @Test
    void shouldRejectMixedRunIds() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelStarted("other-run", 2, 1),
                modelCompleted(RUN_ID, 3, 1, ModelFinishReason.STOP, 0),
                runCompleted(RUN_ID, 4, 1)
        ));
    }

    @Test
    void shouldRejectSequenceGap() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelStarted(RUN_ID, 2, 1),
                modelCompleted(RUN_ID, 4, 1, ModelFinishReason.STOP, 0),
                runCompleted(RUN_ID, 5, 1)
        ));
    }

    @Test
    void shouldRejectDuplicateSequence() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelStarted(RUN_ID, 2, 1),
                modelCompleted(RUN_ID, 2, 1, ModelFinishReason.STOP, 0),
                runCompleted(RUN_ID, 3, 1)
        ));
    }

    @Test
    void shouldRejectMultipleTerminalEvents() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                runCompleted(RUN_ID, 2, 0),
                runStopped(RUN_ID, 3, 0, AgentStopReason.INTERNAL_ERROR, null)
        ));
    }

    @Test
    void shouldRejectTerminalEventThatIsNotLast() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                runCompleted(RUN_ID, 2, 0),
                modelStarted(RUN_ID, 3, 1),
                modelFailed(RUN_ID, 4, 1),
                modelStarted(RUN_ID, 5, 2),
                modelFailed(RUN_ID, 6, 2)
        ));
    }

    @Test
    void shouldRejectModelCompletedWithoutModelStarted() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelCompleted(RUN_ID, 2, 1, ModelFinishReason.STOP, 0),
                runCompleted(RUN_ID, 3, 1)
        ));
    }

    @Test
    void shouldRejectModelFailedWithoutModelStarted() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelFailed(RUN_ID, 2, 1),
                runStopped(RUN_ID, 3, 1, AgentStopReason.MODEL_ERROR, null)
        ));
    }

    @Test
    void shouldRejectSecondModelStartBeforeFirstFinishes() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelStarted(RUN_ID, 2, 1),
                modelStarted(RUN_ID, 3, 1),
                modelCompleted(RUN_ID, 4, 1, ModelFinishReason.STOP, 0),
                runCompleted(RUN_ID, 5, 1)
        ));
    }

    @Test
    void shouldRejectTwoModelTerminalEventsForOneIteration() {
        assertRejected(List.of(
                runStarted(RUN_ID, 1),
                modelStarted(RUN_ID, 2, 1),
                modelCompleted(RUN_ID, 3, 1, ModelFinishReason.STOP, 0),
                modelFailed(RUN_ID, 4, 1),
                runStopped(RUN_ID, 5, 1, AgentStopReason.MODEL_ERROR, null)
        ));
    }

    @Test
    void shouldRejectToolSucceededWithoutToolStarted() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolSucceeded(5, CALL_ID, TOOL_NAME, 1));
        events.add(runCompleted(RUN_ID, 6, 1));
        assertRejected(events);
    }

    @Test
    void shouldRejectPolicyBeforeValidation() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolPolicy(5, CALL_ID, TOOL_NAME, 1, ToolPolicyDecisionType.ALLOW));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.INTERNAL_ERROR, null));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolStartedBeforePolicyAllow() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolStarted(6, CALL_ID, TOOL_NAME, 1));
        events.add(runStopped(RUN_ID, 7, 1, AgentStopReason.INTERNAL_ERROR, null));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolStartedAfterPolicyDeny() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolPolicy(6, CALL_ID, TOOL_NAME, 1, ToolPolicyDecisionType.DENY));
        events.add(toolStarted(7, CALL_ID, TOOL_NAME, 1));
        events.add(runStopped(RUN_ID, 8, 1, AgentStopReason.POLICY_BLOCKED, null));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolStartedAfterValidationFailure() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidationFailed(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolStarted(6, CALL_ID, TOOL_NAME, 1));
        events.add(runStopped(RUN_ID, 7, 1, AgentStopReason.TOOL_ERROR,
                ToolErrorCode.INVALID_ARGUMENTS));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolFailedAfterSuccess() {
        List<AgentEvent> events = successfulToolPrefix();
        events.add(toolFailed(9, CALL_ID, TOOL_NAME, 1, ToolErrorCode.EXECUTION_FAILED));
        events.add(runStopped(RUN_ID, 10, 1, AgentStopReason.TOOL_ERROR,
                ToolErrorCode.EXECUTION_FAILED));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolCallIdMismatch() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, "other-call", TOOL_NAME, 1));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.INTERNAL_ERROR, null));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolNameMismatch() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, "other_tool", 1));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.INTERNAL_ERROR, null));
        assertRejected(events);
    }

    @Test
    void shouldRejectToolIterationMismatch() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 2));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.INTERNAL_ERROR, null));
        assertRejected(events);
    }

    @Test
    void shouldAcceptUnknownToolPath() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolFailed(5, CALL_ID, TOOL_NAME, 1, ToolErrorCode.TOOL_NOT_FOUND));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.TOOL_ERROR,
                ToolErrorCode.TOOL_NOT_FOUND));

        ToolDecisionTrace decision = builder.build(events).orderedToolDecisions().get(0);

        assertEquals(ToolExecutionOutcome.FAILED, decision.executionOutcome());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, decision.errorCode());
        assertNull(decision.policyDecision());
    }

    @Test
    void shouldAcceptInvalidArgumentsPath() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidationFailed(5, CALL_ID, TOOL_NAME, 1));
        events.add(runStopped(RUN_ID, 6, 1, AgentStopReason.TOOL_ERROR,
                ToolErrorCode.INVALID_ARGUMENTS));

        ToolDecisionTrace decision = builder.build(events).orderedToolDecisions().get(0);

        assertEquals(ToolExecutionOutcome.VALIDATION_FAILED, decision.executionOutcome());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, decision.errorCode());
        assertNull(decision.policyDecision());
    }

    @Test
    void shouldAcceptPolicyDenyPath() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolPolicy(6, CALL_ID, TOOL_NAME, 1, ToolPolicyDecisionType.DENY));
        events.add(runStopped(RUN_ID, 7, 1, AgentStopReason.POLICY_BLOCKED, null));

        ToolDecisionTrace decision = builder.build(events).orderedToolDecisions().get(0);

        assertEquals(ToolPolicyDecisionType.DENY, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.BLOCKED, decision.executionOutcome());
    }

    @Test
    void shouldAcceptApprovalPath() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolPolicy(6, CALL_ID, TOOL_NAME, 1,
                ToolPolicyDecisionType.REQUIRE_APPROVAL));
        events.add(runWaitingApproval(RUN_ID, 7, 1));

        DecisionTrace trace = builder.build(events);
        ToolDecisionTrace decision = trace.orderedToolDecisions().get(0);

        assertEquals(ToolPolicyDecisionType.REQUIRE_APPROVAL, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.NOT_STARTED, decision.executionOutcome());
        assertTrue(trace.waitingApproval());
    }

    @Test
    void shouldAcceptSuccessfulToolPath() {
        List<AgentEvent> events = successfulToolPrefix();
        events.add(modelStarted(RUN_ID, 9, 2));
        events.add(modelCompleted(RUN_ID, 10, 2, ModelFinishReason.STOP, 0));
        events.add(runCompleted(RUN_ID, 11, 2));

        DecisionTrace trace = builder.build(events);
        ToolDecisionTrace decision = trace.orderedToolDecisions().get(0);

        assertEquals(2, trace.iterations());
        assertEquals(ToolPolicyDecisionType.ALLOW, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.SUCCEEDED, decision.executionOutcome());
        assertFalse(trace.waitingApproval());
    }

    @Test
    void shouldAcceptToolExecutionFailurePath() {
        List<AgentEvent> events = allowedStartedToolPrefix();
        events.add(toolFailed(8, CALL_ID, TOOL_NAME, 1, ToolErrorCode.EXECUTION_FAILED));
        events.add(runStopped(RUN_ID, 9, 1, AgentStopReason.TOOL_ERROR,
                ToolErrorCode.EXECUTION_FAILED));

        ToolDecisionTrace decision = builder.build(events).orderedToolDecisions().get(0);

        assertEquals(ToolExecutionOutcome.FAILED, decision.executionOutcome());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, decision.errorCode());
    }

    private void assertRejected(List<AgentEvent> events) {
        assertThrows(IllegalArgumentException.class, () -> builder.build(events));
    }

    private List<AgentEvent> toolRequestPrefix() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(runStarted(RUN_ID, 1));
        events.add(modelStarted(RUN_ID, 2, 1));
        events.add(modelCompleted(RUN_ID, 3, 1, ModelFinishReason.TOOL_CALLS, 1));
        events.add(toolRequested(4, CALL_ID, TOOL_NAME, 1));
        return events;
    }

    private List<AgentEvent> allowedStartedToolPrefix() {
        List<AgentEvent> events = toolRequestPrefix();
        events.add(toolValidated(5, CALL_ID, TOOL_NAME, 1));
        events.add(toolPolicy(6, CALL_ID, TOOL_NAME, 1, ToolPolicyDecisionType.ALLOW));
        events.add(toolStarted(7, CALL_ID, TOOL_NAME, 1));
        return events;
    }

    private List<AgentEvent> successfulToolPrefix() {
        List<AgentEvent> events = allowedStartedToolPrefix();
        events.add(toolSucceeded(8, CALL_ID, TOOL_NAME, 1));
        return events;
    }

    private static AgentEventMetadata metadata(String runId, long sequence, int iteration) {
        return new AgentEventMetadata(
                "event-" + sequence + "-" + runId,
                runId,
                sequence,
                Instant.EPOCH.plusSeconds(sequence),
                iteration
        );
    }

    private static RunStartedEvent runStarted(String runId, long sequence) {
        return new RunStartedEvent(metadata(runId, sequence, 0));
    }

    private static ModelStartedEvent modelStarted(String runId, long sequence, int iteration) {
        return new ModelStartedEvent(metadata(runId, sequence, iteration));
    }

    private static ModelCompletedEvent modelCompleted(
            String runId,
            long sequence,
            int iteration,
            ModelFinishReason reason,
            int toolCallCount
    ) {
        return new ModelCompletedEvent(
                metadata(runId, sequence, iteration),
                reason,
                toolCallCount,
                0,
                0,
                0
        );
    }

    private static ModelFailedEvent modelFailed(String runId, long sequence, int iteration) {
        return new ModelFailedEvent(
                metadata(runId, sequence, iteration),
                AgentStopReason.MODEL_ERROR
        );
    }

    private static ToolRequestedEvent toolRequested(
            long sequence,
            String callId,
            String toolName,
            int iteration
    ) {
        return new ToolRequestedEvent(metadata(RUN_ID, sequence, iteration), callId, toolName);
    }

    private static ToolValidatedEvent toolValidated(
            long sequence,
            String callId,
            String toolName,
            int iteration
    ) {
        return new ToolValidatedEvent(metadata(RUN_ID, sequence, iteration), callId, toolName);
    }

    private static ToolValidationFailedEvent toolValidationFailed(
            long sequence,
            String callId,
            String toolName,
            int iteration
    ) {
        return new ToolValidationFailedEvent(
                metadata(RUN_ID, sequence, iteration),
                callId,
                toolName,
                ToolErrorCode.INVALID_ARGUMENTS
        );
    }

    private static ToolPolicyEvaluatedEvent toolPolicy(
            long sequence,
            String callId,
            String toolName,
            int iteration,
            ToolPolicyDecisionType decision
    ) {
        return new ToolPolicyEvaluatedEvent(
                metadata(RUN_ID, sequence, iteration),
                callId,
                toolName,
                decision
        );
    }

    private static ToolStartedEvent toolStarted(
            long sequence,
            String callId,
            String toolName,
            int iteration
    ) {
        return new ToolStartedEvent(metadata(RUN_ID, sequence, iteration), callId, toolName);
    }

    private static ToolSucceededEvent toolSucceeded(
            long sequence,
            String callId,
            String toolName,
            int iteration
    ) {
        return new ToolSucceededEvent(metadata(RUN_ID, sequence, iteration), callId, toolName);
    }

    private static ToolFailedEvent toolFailed(
            long sequence,
            String callId,
            String toolName,
            int iteration,
            ToolErrorCode errorCode
    ) {
        return new ToolFailedEvent(
                metadata(RUN_ID, sequence, iteration),
                callId,
                toolName,
                errorCode
        );
    }

    private static RunCompletedEvent runCompleted(String runId, long sequence, int iteration) {
        return new RunCompletedEvent(metadata(runId, sequence, iteration));
    }

    private static RunStoppedEvent runStopped(
            String runId,
            long sequence,
            int iteration,
            AgentStopReason reason,
            ToolErrorCode errorCode
    ) {
        return new RunStoppedEvent(metadata(runId, sequence, iteration), reason, errorCode);
    }

    private static RunWaitingApprovalEvent runWaitingApproval(
            String runId,
            long sequence,
            int iteration
    ) {
        return new RunWaitingApprovalEvent(metadata(runId, sequence, iteration));
    }
}
