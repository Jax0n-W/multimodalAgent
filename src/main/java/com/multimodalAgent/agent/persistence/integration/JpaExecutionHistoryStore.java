package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.harness.AgentExecutionRequest;
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
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA projection of Runtime execution facts. Every public operation owns one short transaction.
 */
@Component
public class JpaExecutionHistoryStore implements ExecutionHistoryStore {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ToolExecutionRepository toolExecutionRepository;

    public JpaExecutionHistoryStore(
            AgentRunRepository runRepository,
            AgentStepRepository stepRepository,
            ToolExecutionRepository toolExecutionRepository
    ) {
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.stepRepository = Objects.requireNonNull(
                stepRepository,
                "stepRepository must not be null"
        );
        this.toolExecutionRepository = Objects.requireNonNull(
                toolExecutionRepository,
                "toolExecutionRepository must not be null"
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void admit(AgentExecutionRequest request) {
        AgentRunEntity run = new AgentRunEntity(
                request.runSpec().runId(),
                request.requestId(),
                request.userId(),
                request.runSpec().sessionId(),
                AgentRunStatus.CREATED,
                AgentRunPhase.RECEIVED
        );
        run.setCurrentIteration(0);
        runRepository.saveAndFlush(run);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AgentEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        AgentRunEntity run = requireRun(event.runId());

        if (event instanceof RunStartedEvent) {
            run.setStatus(AgentRunStatus.RUNNING);
            run.setPhase(AgentRunPhase.RECEIVED);
            run.setStartedAt(event.occurredAt());
        } else if (event instanceof ModelStartedEvent) {
            recordModelStarted(run, event);
        } else if (event instanceof ModelCompletedEvent completed) {
            recordModelCompleted(run, completed);
        } else if (event instanceof ModelFailedEvent failed) {
            recordModelFailed(run, failed);
        } else if (event instanceof ToolRequestedEvent requested) {
            recordToolRequested(run, requested);
        } else if (event instanceof ToolValidatedEvent) {
            updateRunProgress(run, event.iteration(), AgentRunPhase.AWAITING_TOOL);
        } else if (event instanceof ToolValidationFailedEvent failed) {
            recordNonExecutedToolFailure(run, failed.toolCallId(), failed.errorCode(), event);
        } else if (event instanceof ToolPolicyEvaluatedEvent evaluated) {
            recordPolicyDecision(run, evaluated);
        } else if (event instanceof ToolStartedEvent started) {
            recordToolStarted(run, started);
        } else if (event instanceof ToolSucceededEvent succeeded) {
            recordToolSucceeded(run, succeeded);
        } else if (event instanceof ToolFailedEvent failed) {
            recordToolFailed(run, failed);
        } else if (event instanceof RunCompletedEvent) {
            recordRunCompleted(run, event);
        } else if (event instanceof RunStoppedEvent stopped) {
            recordRunStopped(run, stopped);
        } else if (event instanceof RunWaitingApprovalEvent) {
            run.setStatus(AgentRunStatus.WAITING_APPROVAL);
            run.setPhase(AgentRunPhase.AWAITING_TOOL);
            run.setCurrentIteration(event.iteration());
            run.setStopReason(AgentStopReason.WAITING_APPROVAL);
        }

        runRepository.saveAndFlush(run);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRun(String runId, AgentRunResult result) {
        Objects.requireNonNull(result, "result must not be null");
        AgentRunEntity run = requireRun(runId);
        if (run.getStopReason() != result.stopReason()) {
            throw new IllegalStateException(
                    "Persisted terminal truth does not match AgentRunResult for run " + runId
            );
        }
        if (result.stopReason() == AgentStopReason.COMPLETED) {
            run.setFinalContent(result.finalContent());
        }
        runRepository.saveAndFlush(run);
    }

    private void recordModelStarted(AgentRunEntity run, AgentEvent event) {
        updateRunProgress(run, event.iteration(), AgentRunPhase.MODEL_RUNNING);
        AgentStepEntity step = new AgentStepEntity(
                modelStepId(event.runId(), event.iteration()),
                event.runId(),
                event.iteration(),
                nextStepIndex(event.runId()),
                AgentStepType.MODEL,
                AgentStepStatus.RUNNING
        );
        step.setStartedAt(event.occurredAt());
        stepRepository.saveAndFlush(step);
    }

    private void recordModelCompleted(AgentRunEntity run, ModelCompletedEvent event) {
        AgentStepEntity step = requireStep(modelStepId(event.runId(), event.iteration()));
        step.setStatus(AgentStepStatus.SUCCEEDED);
        step.setCompletedAt(event.occurredAt());
        stepRepository.saveAndFlush(step);
        updateRunProgress(
                run,
                event.iteration(),
                event.finishReason() == ModelFinishReason.STOP
                        ? AgentRunPhase.FINALIZING
                        : AgentRunPhase.AWAITING_TOOL
        );
    }

    private void recordModelFailed(AgentRunEntity run, ModelFailedEvent event) {
        AgentStepEntity step = requireStep(modelStepId(event.runId(), event.iteration()));
        step.setStatus(AgentStepStatus.FAILED);
        step.setCompletedAt(event.occurredAt());
        step.setErrorCode(event.stopReason().name());
        stepRepository.saveAndFlush(step);
        updateRunProgress(run, event.iteration(), AgentRunPhase.FINALIZING);
    }

    private void recordToolRequested(AgentRunEntity run, ToolRequestedEvent event) {
        updateRunProgress(run, event.iteration(), AgentRunPhase.AWAITING_TOOL);
        String stepId = toolStepId(event.runId(), event.toolCallId());
        AgentStepEntity step = new AgentStepEntity(
                stepId,
                event.runId(),
                event.iteration(),
                nextStepIndex(event.runId()),
                AgentStepType.TOOL,
                AgentStepStatus.PLANNED
        );
        stepRepository.saveAndFlush(step);
        toolExecutionRepository.saveAndFlush(new ToolExecutionEntity(
                toolExecutionId(event.runId(), event.toolCallId()),
                event.runId(),
                stepId,
                event.toolCallId(),
                event.toolName(),
                ToolExecutionStatus.PLANNED
        ));
    }

    private void recordPolicyDecision(AgentRunEntity run, ToolPolicyEvaluatedEvent event) {
        updateRunProgress(run, event.iteration(), AgentRunPhase.AWAITING_TOOL);
        if (event.decision() == ToolPolicyDecisionType.DENY) {
            markNonExecuted(
                    event.runId(),
                    event.toolCallId(),
                    event.occurredAt(),
                    "POLICY_DENIED"
            );
        }
    }

    private void recordToolStarted(AgentRunEntity run, ToolStartedEvent event) {
        updateRunProgress(run, event.iteration(), AgentRunPhase.TOOL_RUNNING);
        AgentStepEntity step = requireStep(toolStepId(event.runId(), event.toolCallId()));
        step.setStatus(AgentStepStatus.RUNNING);
        step.setStartedAt(event.occurredAt());
        stepRepository.saveAndFlush(step);

        ToolExecutionEntity execution = requireToolExecution(event.runId(), event.toolCallId());
        execution.setStatus(ToolExecutionStatus.STARTED);
        execution.setStartedAt(event.occurredAt());
        toolExecutionRepository.saveAndFlush(execution);
    }

    private void recordToolSucceeded(AgentRunEntity run, ToolSucceededEvent event) {
        AgentStepEntity step = requireStep(toolStepId(event.runId(), event.toolCallId()));
        step.setStatus(AgentStepStatus.SUCCEEDED);
        step.setCompletedAt(event.occurredAt());
        stepRepository.saveAndFlush(step);

        ToolExecutionEntity execution = requireToolExecution(event.runId(), event.toolCallId());
        execution.setStatus(ToolExecutionStatus.SUCCEEDED);
        execution.setCompletedAt(event.occurredAt());
        toolExecutionRepository.saveAndFlush(execution);
        updateRunProgress(run, event.iteration(), AgentRunPhase.AWAITING_TOOL);
    }

    private void recordToolFailed(AgentRunEntity run, ToolFailedEvent event) {
        if (event.errorCode() == ToolErrorCode.TOOL_NOT_FOUND) {
            recordNonExecutedToolFailure(run, event.toolCallId(), event.errorCode(), event);
            return;
        }

        AgentStepEntity step = requireStep(toolStepId(event.runId(), event.toolCallId()));
        step.setStatus(AgentStepStatus.FAILED);
        step.setCompletedAt(event.occurredAt());
        step.setErrorCode(event.errorCode().name());
        stepRepository.saveAndFlush(step);

        ToolExecutionEntity execution = requireToolExecution(event.runId(), event.toolCallId());
        execution.setStatus(ToolExecutionStatus.FAILED);
        execution.setCompletedAt(event.occurredAt());
        execution.setErrorCode(event.errorCode().name());
        toolExecutionRepository.saveAndFlush(execution);
        updateRunProgress(run, event.iteration(), AgentRunPhase.FINALIZING);
    }

    private void recordNonExecutedToolFailure(
            AgentRunEntity run,
            String toolCallId,
            ToolErrorCode errorCode,
            AgentEvent event
    ) {
        markNonExecuted(event.runId(), toolCallId, event.occurredAt(), errorCode.name());
        updateRunProgress(run, event.iteration(), AgentRunPhase.FINALIZING);
    }

    private void markNonExecuted(
            String runId,
            String toolCallId,
            Instant occurredAt,
            String errorCode
    ) {
        AgentStepEntity step = requireStep(toolStepId(runId, toolCallId));
        step.setStatus(AgentStepStatus.SKIPPED);
        step.setCompletedAt(occurredAt);
        step.setErrorCode(errorCode);
        stepRepository.saveAndFlush(step);

        ToolExecutionEntity execution = requireToolExecution(runId, toolCallId);
        execution.setStatus(ToolExecutionStatus.BLOCKED);
        execution.setCompletedAt(occurredAt);
        execution.setErrorCode(errorCode);
        toolExecutionRepository.saveAndFlush(execution);
    }

    private void recordRunCompleted(AgentRunEntity run, AgentEvent event) {
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setPhase(AgentRunPhase.FINALIZING);
        run.setCurrentIteration(event.iteration());
        run.setStopReason(AgentStopReason.COMPLETED);
        run.setCompletedAt(event.occurredAt());
    }

    private void recordRunStopped(AgentRunEntity run, RunStoppedEvent event) {
        run.setStatus(event.stopReason() == AgentStopReason.CANCELLED
                ? AgentRunStatus.CANCELLED
                : AgentRunStatus.FAILED);
        run.setPhase(AgentRunPhase.FINALIZING);
        run.setCurrentIteration(event.iteration());
        run.setStopReason(event.stopReason());
        run.setCompletedAt(event.occurredAt());
    }

    private void updateRunProgress(AgentRunEntity run, int iteration, AgentRunPhase phase) {
        run.setStatus(AgentRunStatus.RUNNING);
        run.setCurrentIteration(iteration);
        run.setPhase(phase);
    }

    private int nextStepIndex(String runId) {
        return Math.toIntExact(stepRepository.countByRunId(runId) + 1L);
    }

    private AgentRunEntity requireRun(String runId) {
        return runRepository.findByRunId(runId).orElseThrow(() ->
                new IllegalStateException("Durable AgentRun not found: " + runId)
        );
    }

    private AgentStepEntity requireStep(String stepId) {
        return stepRepository.findByStepId(stepId).orElseThrow(() ->
                new IllegalStateException("Durable AgentStep not found: " + stepId)
        );
    }

    private ToolExecutionEntity requireToolExecution(String runId, String toolCallId) {
        return toolExecutionRepository.findByRunIdAndToolCallId(runId, toolCallId)
                .orElseThrow(() -> new IllegalStateException(
                        "Durable ToolExecution not found for run/toolCall: "
                                + runId + "/" + toolCallId
                ));
    }

    static String modelStepId(String runId, int iteration) {
        return stableId("model-step", runId, Integer.toString(iteration));
    }

    static String toolStepId(String runId, String toolCallId) {
        return stableId("tool-step", runId, toolCallId);
    }

    static String toolExecutionId(String runId, String toolCallId) {
        return stableId("tool-execution", runId, toolCallId);
    }

    private static String stableId(String kind, String runId, String correlationId) {
        return UUID.nameUUIDFromBytes(
                (kind + "\u0000" + runId + "\u0000" + correlationId)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
