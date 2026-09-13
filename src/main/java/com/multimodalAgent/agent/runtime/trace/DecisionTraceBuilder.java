package com.multimodalAgent.agent.runtime.trace;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
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
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DecisionTraceBuilder {

    public DecisionTrace build(List<AgentEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        if (events.isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }

        List<AgentEvent> snapshot = List.copyOf(events);
        String runId = snapshot.get(0).runId();
        if (snapshot.get(0).type() != AgentEventType.RUN_STARTED) {
            throw new IllegalArgumentException("The first event must be RUN_STARTED");
        }

        Map<String, MutableToolDecision> tools = new LinkedHashMap<>();
        List<DecisionTraceError> errors = new ArrayList<>();
        int iterations = 0;
        int modelCallCount = 0;
        int terminalCount = 0;
        AgentStopReason stopReason = null;
        boolean waitingApproval = false;
        ModelLifecycle modelLifecycle = new ModelLifecycle();

        for (int index = 0; index < snapshot.size(); index++) {
            AgentEvent event = Objects.requireNonNull(snapshot.get(index), "event must not be null");
            if (!runId.equals(event.runId())) {
                throw new IllegalArgumentException("All events must belong to the same runId");
            }
            long expectedSequence = index + 1L;
            if (event.sequence() != expectedSequence) {
                throw new IllegalArgumentException("Event sequence must be contiguous and start at 1");
            }

            if (event instanceof RunStartedEvent) {
                if (index != 0) {
                    throw new IllegalArgumentException("RUN_STARTED may only appear once as the first event");
                }
            } else if (event instanceof ModelStartedEvent) {
                modelLifecycle.start(event.iteration());
                modelCallCount++;
                iterations = Math.max(iterations, event.iteration());
            } else if (event instanceof ModelCompletedEvent) {
                modelLifecycle.complete(event.iteration());
            } else if (event instanceof ModelFailedEvent failed) {
                modelLifecycle.fail(event.iteration());
                errors.add(new DecisionTraceError(
                        event.sequence(),
                        event.iteration(),
                        DecisionTraceErrorSource.MODEL,
                        null,
                        null,
                        failed.stopReason().name()
                ));
            } else if (event instanceof ToolRequestedEvent requested) {
                MutableToolDecision previous = tools.putIfAbsent(
                        requested.toolCallId(),
                        new MutableToolDecision(
                                requested.toolCallId(),
                                requested.toolName(),
                                requested.iteration()
                        )
                );
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate toolCallId: " + requested.toolCallId());
                }
            } else if (event instanceof ToolValidatedEvent validated) {
                MutableToolDecision tool = requireTool(
                        tools,
                        validated.toolCallId(),
                        validated.toolName(),
                        validated.iteration()
                );
                tool.transition(ToolTraceState.REQUESTED, ToolTraceState.VALIDATED, event.type());
            } else if (event instanceof ToolValidationFailedEvent failed) {
                MutableToolDecision tool = requireTool(
                        tools,
                        failed.toolCallId(),
                        failed.toolName(),
                        failed.iteration()
                );
                tool.transition(
                        ToolTraceState.REQUESTED,
                        ToolTraceState.VALIDATION_FAILED,
                        event.type()
                );
                tool.outcome = ToolExecutionOutcome.VALIDATION_FAILED;
                tool.errorCode = failed.errorCode();
                errors.add(new DecisionTraceError(
                        event.sequence(),
                        event.iteration(),
                        DecisionTraceErrorSource.VALIDATION,
                        failed.toolCallId(),
                        failed.toolName(),
                        failed.errorCode().name()
                ));
            } else if (event instanceof ToolPolicyEvaluatedEvent evaluated) {
                MutableToolDecision tool = requireTool(
                        tools,
                        evaluated.toolCallId(),
                        evaluated.toolName(),
                        evaluated.iteration()
                );
                ToolTraceState nextState = switch (evaluated.decision()) {
                    case ALLOW -> ToolTraceState.POLICY_ALLOW;
                    case DENY -> ToolTraceState.POLICY_DENY;
                    case REQUIRE_APPROVAL -> ToolTraceState.POLICY_REQUIRE_APPROVAL;
                };
                tool.transition(ToolTraceState.VALIDATED, nextState, event.type());
                tool.policyDecision = evaluated.decision();
                if (evaluated.decision() == ToolPolicyDecisionType.DENY) {
                    tool.outcome = ToolExecutionOutcome.BLOCKED;
                } else if (evaluated.decision() == ToolPolicyDecisionType.REQUIRE_APPROVAL) {
                    tool.outcome = ToolExecutionOutcome.NOT_STARTED;
                }
            } else if (event instanceof ToolStartedEvent started) {
                MutableToolDecision tool = requireTool(
                        tools,
                        started.toolCallId(),
                        started.toolName(),
                        started.iteration()
                );
                tool.transition(ToolTraceState.POLICY_ALLOW, ToolTraceState.STARTED, event.type());
                tool.outcome = ToolExecutionOutcome.STARTED;
            } else if (event instanceof ToolSucceededEvent succeeded) {
                MutableToolDecision tool = requireTool(
                        tools,
                        succeeded.toolCallId(),
                        succeeded.toolName(),
                        succeeded.iteration()
                );
                tool.transition(ToolTraceState.STARTED, ToolTraceState.SUCCEEDED, event.type());
                tool.outcome = ToolExecutionOutcome.SUCCEEDED;
            } else if (event instanceof ToolFailedEvent failed) {
                MutableToolDecision tool = requireTool(
                        tools,
                        failed.toolCallId(),
                        failed.toolName(),
                        failed.iteration()
                );
                if (failed.errorCode() == ToolErrorCode.TOOL_NOT_FOUND) {
                    tool.transition(ToolTraceState.REQUESTED, ToolTraceState.FAILED, event.type());
                } else if (failed.errorCode() == ToolErrorCode.EXECUTION_FAILED) {
                    tool.transition(ToolTraceState.STARTED, ToolTraceState.FAILED, event.type());
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported ToolFailedEvent error code: " + failed.errorCode()
                    );
                }
                tool.outcome = ToolExecutionOutcome.FAILED;
                tool.errorCode = failed.errorCode();
                errors.add(new DecisionTraceError(
                        event.sequence(),
                        event.iteration(),
                        DecisionTraceErrorSource.TOOL,
                        failed.toolCallId(),
                        failed.toolName(),
                        failed.errorCode().name()
                ));
            } else if (event instanceof RunCompletedEvent) {
                terminalCount++;
                stopReason = AgentStopReason.COMPLETED;
            } else if (event instanceof RunWaitingApprovalEvent) {
                terminalCount++;
                stopReason = AgentStopReason.WAITING_APPROVAL;
                waitingApproval = true;
            } else if (event instanceof RunStoppedEvent stopped) {
                terminalCount++;
                stopReason = stopped.stopReason();
            }
        }

        if (terminalCount != 1) {
            throw new IllegalArgumentException("Exactly one terminal run event is required");
        }
        modelLifecycle.requireClosed();
        AgentEvent lastEvent = snapshot.get(snapshot.size() - 1);
        if (lastEvent.type() != AgentEventType.RUN_COMPLETED
                && lastEvent.type() != AgentEventType.RUN_STOPPED
                && lastEvent.type() != AgentEventType.RUN_WAITING_APPROVAL) {
            throw new IllegalArgumentException("The terminal run event must be last");
        }
        boolean runCompleted = lastEvent.type() == AgentEventType.RUN_COMPLETED;
        AgentStopReason terminalReason = stopReason;
        tools.values().forEach(tool -> tool.requireTerminallyConsistent(runCompleted, terminalReason));

        List<ToolDecisionTrace> toolDecisions = tools.values().stream()
                .map(MutableToolDecision::toTrace)
                .toList();
        return new DecisionTrace(
                runId,
                snapshot.size(),
                iterations,
                modelCallCount,
                tools.size(),
                stopReason,
                waitingApproval,
                toolDecisions,
                errors
        );
    }

    private MutableToolDecision requireTool(
            Map<String, MutableToolDecision> tools,
            String toolCallId,
            String toolName,
            int iteration
    ) {
        MutableToolDecision tool = tools.get(toolCallId);
        if (tool == null) {
            throw new IllegalArgumentException("Tool lifecycle event has no request: " + toolCallId);
        }
        if (!tool.toolName.equals(toolName) || tool.iteration != iteration) {
            throw new IllegalArgumentException("Tool lifecycle correlation does not match request");
        }
        return tool;
    }

    private static final class MutableToolDecision {

        private final String toolCallId;
        private final String toolName;
        private final int iteration;
        private ToolPolicyDecisionType policyDecision;
        private ToolExecutionOutcome outcome = ToolExecutionOutcome.NOT_STARTED;
        private ToolErrorCode errorCode;
        private ToolTraceState state = ToolTraceState.REQUESTED;

        private MutableToolDecision(String toolCallId, String toolName, int iteration) {
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.iteration = iteration;
        }

        private void transition(
                ToolTraceState expectedState,
                ToolTraceState nextState,
                AgentEventType eventType
        ) {
            if (state != expectedState) {
                throw new IllegalArgumentException(
                        "Illegal tool lifecycle transition for " + toolCallId
                                + ": " + state + " -> " + eventType
                );
            }
            state = nextState;
        }

        private void requireTerminallyConsistent(
                boolean runCompleted,
                AgentStopReason terminalReason
        ) {
            if (state == ToolTraceState.VALIDATED
                    || state == ToolTraceState.STARTED) {
                throw new IllegalArgumentException(
                        "Tool lifecycle is incomplete for " + toolCallId + ": " + state
                );
            }
            if (state == ToolTraceState.POLICY_ALLOW
                    && terminalReason != AgentStopReason.INTERNAL_ERROR) {
                throw new IllegalArgumentException(
                        "Tool lifecycle is incomplete for " + toolCallId + ": " + state
                );
            }
            if (runCompleted && state != ToolTraceState.SUCCEEDED) {
                throw new IllegalArgumentException(
                        "RUN_COMPLETED requires every requested tool to succeed: " + toolCallId
                );
            }
        }

        private ToolDecisionTrace toTrace() {
            return new ToolDecisionTrace(
                    toolCallId,
                    toolName,
                    iteration,
                    policyDecision,
                    outcome,
                    errorCode
            );
        }
    }

    private enum ToolTraceState {
        REQUESTED,
        VALIDATED,
        POLICY_ALLOW,
        POLICY_DENY,
        POLICY_REQUIRE_APPROVAL,
        STARTED,
        SUCCEEDED,
        FAILED,
        VALIDATION_FAILED
    }

    private static final class ModelLifecycle {

        private int expectedIteration = 1;
        private Integer activeIteration;

        private void start(int iteration) {
            if (activeIteration != null) {
                throw new IllegalArgumentException(
                        "MODEL_STARTED cannot occur before the active model invocation finishes"
                );
            }
            if (iteration != expectedIteration) {
                throw new IllegalArgumentException(
                        "Model iterations must start at 1 and increase by one"
                );
            }
            activeIteration = iteration;
        }

        private void complete(int iteration) {
            finish(iteration, AgentEventType.MODEL_COMPLETED);
        }

        private void fail(int iteration) {
            finish(iteration, AgentEventType.MODEL_FAILED);
        }

        private void finish(int iteration, AgentEventType eventType) {
            if (activeIteration == null || activeIteration != iteration) {
                throw new IllegalArgumentException(
                        eventType + " has no matching MODEL_STARTED for iteration " + iteration
                );
            }
            activeIteration = null;
            expectedIteration++;
        }

        private void requireClosed() {
            if (activeIteration != null) {
                throw new IllegalArgumentException(
                        "Model invocation has no MODEL_COMPLETED or MODEL_FAILED event"
                );
            }
        }
    }
}
