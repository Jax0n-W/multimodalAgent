package com.multimodalAgent.agent.runtime.trace;

import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.BudgetBlockReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.BudgetBlockedEvent;
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
        Map<Integer, ModelCompletion> modelCompletions = new LinkedHashMap<>();
        Map<Integer, Integer> requestedToolCounts = new LinkedHashMap<>();
        boolean childRequiresRunTerminal = false;

        for (int index = 0; index < snapshot.size(); index++) {
            AgentEvent event = Objects.requireNonNull(snapshot.get(index), "event must not be null");
            if (!runId.equals(event.runId())) {
                throw new IllegalArgumentException("All events must belong to the same runId");
            }
            long expectedSequence = index + 1L;
            if (event.sequence() != expectedSequence) {
                throw new IllegalArgumentException("Event sequence must be contiguous and start at 1");
            }
            if (childRequiresRunTerminal) {
                if (!(event instanceof RunStoppedEvent)
                        && !(event instanceof RunWaitingApprovalEvent)) {
                    throw new IllegalArgumentException(
                            "No core work may follow a terminal child outcome"
                    );
                }
                childRequiresRunTerminal = false;
            }

            if (event instanceof RunStartedEvent) {
                if (index != 0) {
                    throw new IllegalArgumentException("RUN_STARTED may only appear once as the first event");
                }
                if (event.iteration() != 0) {
                    throw new IllegalArgumentException("RUN_STARTED iteration must be 0");
                }
            } else if (event instanceof ModelStartedEvent) {
                modelLifecycle.start(event.iteration());
                modelCallCount++;
                iterations = Math.max(iterations, event.iteration());
            } else if (event instanceof ModelCompletedEvent completed) {
                modelLifecycle.complete(event.iteration());
                if ((completed.finishReason() == ModelFinishReason.STOP
                        || completed.finishReason() == ModelFinishReason.LENGTH)
                        && completed.toolCallCount() != 0) {
                    throw new IllegalArgumentException(
                            "A terminal text model completion cannot declare tool calls"
                    );
                }
                modelCompletions.put(
                        event.iteration(),
                        new ModelCompletion(completed, index)
                );
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
                childRequiresRunTerminal = true;
            } else if (event instanceof ToolRequestedEvent requested) {
                ModelCompletion completion = modelCompletions.get(requested.iteration());
                if (completion == null
                        || completion.event().finishReason()
                        != ModelFinishReason.TOOL_CALLS) {
                    throw new IllegalArgumentException(
                            "TOOL_REQUESTED requires a TOOL_CALLS model completion in the same iteration"
                    );
                }
                int requestCount = requestedToolCounts.merge(requested.iteration(), 1, Integer::sum);
                if (requestCount > completion.event().toolCallCount()) {
                    throw new IllegalArgumentException(
                            "TOOL_REQUESTED count exceeds the model-declared toolCallCount"
                    );
                }
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
                childRequiresRunTerminal = true;
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
                    childRequiresRunTerminal = true;
                } else if (evaluated.decision() == ToolPolicyDecisionType.REQUIRE_APPROVAL) {
                    tool.outcome = ToolExecutionOutcome.NOT_STARTED;
                    childRequiresRunTerminal = true;
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
                childRequiresRunTerminal = true;
            } else if (event instanceof BudgetBlockedEvent blocked) {
                if (blocked.toolCorrelated()) {
                    MutableToolDecision tool = requireTool(
                            tools,
                            blocked.toolCallId().orElseThrow(),
                            blocked.toolName().orElseThrow(),
                            blocked.iteration()
                    );
                    tool.transition(
                            ToolTraceState.POLICY_ALLOW,
                            ToolTraceState.BUDGET_BLOCKED,
                            event.type()
                    );
                    tool.outcome = ToolExecutionOutcome.BLOCKED;
                } else {
                    validateModelAdmissionBudgetBlock(snapshot, index, blocked);
                }
                errors.add(new DecisionTraceError(
                        event.sequence(),
                        event.iteration(),
                        DecisionTraceErrorSource.BUDGET,
                        blocked.toolCallId().orElse(null),
                        blocked.toolName().orElse(null),
                        blocked.reason().name() + ":" + blocked.dimension().name()
                ));
                childRequiresRunTerminal = true;
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
        if (childRequiresRunTerminal) {
            throw new IllegalArgumentException("A failure or policy decision has no matching run terminal");
        }
        modelLifecycle.requireClosed();
        AgentEvent lastEvent = snapshot.get(snapshot.size() - 1);
        if (lastEvent.type() != AgentEventType.RUN_COMPLETED
                && lastEvent.type() != AgentEventType.RUN_STOPPED
                && lastEvent.type() != AgentEventType.RUN_WAITING_APPROVAL) {
            throw new IllegalArgumentException("The terminal run event must be last");
        }
        validateDeclaredToolCallCounts(snapshot, modelCompletions, requestedToolCounts);
        validateMultiToolOrdering(snapshot);
        validateTerminalCause(snapshot);
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

    private void validateMultiToolOrdering(List<AgentEvent> events) {
        Map<Integer, ToolOrdering> orderings = new LinkedHashMap<>();
        for (AgentEvent event : events) {
            if (event instanceof ToolRequestedEvent requested) {
                orderings.computeIfAbsent(event.iteration(), ignored -> new ToolOrdering())
                        .request(requested.toolCallId());
                continue;
            }

            String toolCallId = toolCallId(event);
            if (toolCallId == null) {
                continue;
            }
            ToolOrdering ordering = orderings.get(event.iteration());
            if (ordering == null) {
                throw new IllegalArgumentException("Tool lifecycle has no ordered request batch");
            }
            boolean terminal = event instanceof ToolValidationFailedEvent
                    || event instanceof ToolSucceededEvent
                    || event instanceof ToolFailedEvent
                    || event instanceof BudgetBlockedEvent
                    || event instanceof ToolPolicyEvaluatedEvent evaluated
                    && evaluated.decision() != ToolPolicyDecisionType.ALLOW;
            ordering.lifecycle(toolCallId, terminal);
        }
    }

    private void validateModelAdmissionBudgetBlock(
            List<AgentEvent> events,
            int index,
            BudgetBlockedEvent blocked
    ) {
        AgentEvent previous = index == 0 ? null : events.get(index - 1);
        boolean firstModelAdmission = previous instanceof RunStartedEvent
                && blocked.iteration() == 1;
        boolean laterModelAdmission = previous instanceof ToolSucceededEvent
                && blocked.iteration() == previous.iteration() + 1;
        if (!firstModelAdmission && !laterModelAdmission) {
            throw new IllegalArgumentException(
                    "A run-level budget block must occur at a model admission boundary"
            );
        }
    }

    private String toolCallId(AgentEvent event) {
        if (event instanceof ToolValidatedEvent value) {
            return value.toolCallId();
        }
        if (event instanceof ToolValidationFailedEvent value) {
            return value.toolCallId();
        }
        if (event instanceof ToolPolicyEvaluatedEvent value) {
            return value.toolCallId();
        }
        if (event instanceof ToolStartedEvent value) {
            return value.toolCallId();
        }
        if (event instanceof ToolSucceededEvent value) {
            return value.toolCallId();
        }
        if (event instanceof ToolFailedEvent value) {
            return value.toolCallId();
        }
        if (event instanceof BudgetBlockedEvent value) {
            return value.toolCallId().orElse(null);
        }
        return null;
    }

    private void validateDeclaredToolCallCounts(
            List<AgentEvent> events,
            Map<Integer, ModelCompletion> modelCompletions,
            Map<Integer, Integer> requestedToolCounts
    ) {
        for (Map.Entry<Integer, ModelCompletion> entry : modelCompletions.entrySet()) {
            ModelCompletedEvent completed = entry.getValue().event();
            if (completed.finishReason()
                    != ModelFinishReason.TOOL_CALLS) {
                continue;
            }
            int actualCount = requestedToolCounts.getOrDefault(entry.getKey(), 0);
            if (actualCount == completed.toolCallCount()) {
                continue;
            }
            int nextIndex = entry.getValue().eventIndex() + 1;
            boolean modelMiddlewarePostFailure = nextIndex < events.size()
                    && events.get(nextIndex) instanceof RunStoppedEvent stopped
                    && stopped.stopReason() == AgentStopReason.INTERNAL_ERROR;
            if (!modelMiddlewarePostFailure) {
                throw new IllegalArgumentException(
                        "TOOL_REQUESTED count does not match the model-declared toolCallCount"
                );
            }
        }
    }

    private void validateTerminalCause(List<AgentEvent> events) {
        AgentEvent terminal = events.get(events.size() - 1);
        AgentEvent cause = events.size() == 1 ? null : events.get(events.size() - 2);

        if (terminal instanceof RunCompletedEvent) {
            if (!(cause instanceof ModelCompletedEvent completed)
                    || completed.finishReason()
                    != ModelFinishReason.STOP) {
                throw new IllegalArgumentException("RUN_COMPLETED requires a final STOP model turn");
            }
            requireSameIteration(terminal, cause);
            return;
        }
        if (terminal instanceof RunWaitingApprovalEvent) {
            if (!(cause instanceof ToolPolicyEvaluatedEvent evaluated)
                    || evaluated.decision() != ToolPolicyDecisionType.REQUIRE_APPROVAL) {
                throw new IllegalArgumentException(
                        "RUN_WAITING_APPROVAL requires a REQUIRE_APPROVAL policy decision"
                );
            }
            requireSameIteration(terminal, cause);
            return;
        }

        RunStoppedEvent stopped = (RunStoppedEvent) terminal;
        switch (stopped.stopReason()) {
            case MODEL_ERROR, MODEL_TIMEOUT -> {
                requireCause(cause instanceof ModelFailedEvent,
                        stopped.stopReason() + " requires MODEL_FAILED");
                requireSameIteration(terminal, cause);
            }
            case MODEL_OUTPUT_LIMIT -> {
                requireCause(cause instanceof ModelCompletedEvent completed
                                && completed.finishReason() == ModelFinishReason.LENGTH,
                        "MODEL_OUTPUT_LIMIT requires a LENGTH model completion");
                requireSameIteration(terminal, cause);
            }
            case TOOL_ERROR -> {
                ToolErrorCode causeCode;
                if (cause instanceof ToolFailedEvent failed) {
                    causeCode = failed.errorCode();
                } else if (cause instanceof ToolValidationFailedEvent failed) {
                    causeCode = failed.errorCode();
                } else {
                    throw new IllegalArgumentException(
                            "TOOL_ERROR requires TOOL_FAILED or TOOL_VALIDATION_FAILED"
                    );
                }
                requireCause(
                        causeCode == stopped.toolErrorCode(),
                        "TOOL_ERROR code must match its tool failure"
                );
                requireSameIteration(terminal, cause);
            }
            case POLICY_BLOCKED -> {
                requireCause(cause instanceof ToolPolicyEvaluatedEvent evaluated
                                && evaluated.decision() == ToolPolicyDecisionType.DENY,
                        "POLICY_BLOCKED requires a DENY policy decision");
                requireSameIteration(terminal, cause);
            }
            case BUDGET_EXHAUSTED, BUDGET_UNVERIFIABLE -> {
                BudgetBlockReason requiredReason = stopped.stopReason()
                        == AgentStopReason.BUDGET_EXHAUSTED
                        ? BudgetBlockReason.EXHAUSTED
                        : BudgetBlockReason.UNVERIFIABLE;
                requireCause(cause instanceof BudgetBlockedEvent blocked
                                && blocked.reason() == requiredReason,
                        stopped.stopReason() + " requires a matching BUDGET_BLOCKED event");
                requireSameIteration(terminal, cause);
            }
            case MAX_ITERATIONS -> {
                requireCause(cause instanceof ToolSucceededEvent,
                        "MAX_ITERATIONS requires completion of the final iteration tool work");
                requireSameIteration(terminal, cause);
            }
            case INTERNAL_ERROR -> {
                requireCause(cause instanceof RunStartedEvent
                            || cause instanceof ModelCompletedEvent
                            || cause instanceof ToolSucceededEvent
                            || cause instanceof ToolPolicyEvaluatedEvent evaluated
                            && evaluated.decision() == ToolPolicyDecisionType.ALLOW,
                        "INTERNAL_ERROR must occur at a supported middleware boundary");
                validateInternalErrorIteration(terminal, cause);
            }
            default -> {
                // Other stop reasons are reserved by the runtime contract for future execution paths.
            }
        }
    }

    private void requireCause(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireSameIteration(AgentEvent terminal, AgentEvent cause) {
        requireCause(
                terminal.iteration() == cause.iteration(),
                "Run terminal iteration must match its cause"
        );
    }

    private void validateInternalErrorIteration(AgentEvent terminal, AgentEvent cause) {
        if (cause instanceof RunStartedEvent) {
            requireCause(terminal.iteration() == 1,
                    "Initial model middleware failure must stop in iteration 1");
        } else if (cause instanceof ToolSucceededEvent) {
            requireCause(
                    terminal.iteration() == cause.iteration()
                            || terminal.iteration() == cause.iteration() + 1,
                    "INTERNAL_ERROR iteration does not match its middleware boundary"
            );
        } else {
            requireSameIteration(terminal, cause);
        }
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
        BUDGET_BLOCKED,
        STARTED,
        SUCCEEDED,
        FAILED,
        VALIDATION_FAILED
    }

    private record ModelCompletion(ModelCompletedEvent event, int eventIndex) {
    }

    private static final class ToolOrdering {

        private final List<String> requestedCallIds = new ArrayList<>();
        private boolean lifecycleStarted;
        private int nextCallIndex;
        private String activeCallId;

        private void request(String toolCallId) {
            if (lifecycleStarted) {
                throw new IllegalArgumentException(
                        "All TOOL_REQUESTED events must precede tool execution lifecycle events"
                );
            }
            requestedCallIds.add(toolCallId);
        }

        private void lifecycle(String toolCallId, boolean terminal) {
            lifecycleStarted = true;
            if (activeCallId == null) {
                if (nextCallIndex >= requestedCallIds.size()
                        || !requestedCallIds.get(nextCallIndex).equals(toolCallId)) {
                    throw new IllegalArgumentException(
                            "Tool lifecycles must follow model request order"
                    );
                }
                activeCallId = toolCallId;
            } else if (!activeCallId.equals(toolCallId)) {
                throw new IllegalArgumentException("Tool execution lifecycles must not interleave");
            }

            if (terminal) {
                activeCallId = null;
                nextCallIndex++;
            }
        }
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
