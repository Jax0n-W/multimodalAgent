package com.multimodalAgent.agent.runtime;

import com.multimodalAgent.agent.runtime.control.ExecutionCancelledException;
import com.multimodalAgent.agent.runtime.control.ExecutionCheckpoint;
import com.multimodalAgent.agent.runtime.control.RuntimeCancellation;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.event.ModelFailedEvent;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.NoopAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.RunCompletedEvent;
import com.multimodalAgent.agent.runtime.event.RunStartedEvent;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.event.RunWaitingApprovalEvent;
import com.multimodalAgent.agent.runtime.event.ToolRequestedEvent;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.GovernedAgentModel;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationException;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class AgentRunner {

    private final AgentModel model;
    private final ToolExecutor toolExecutor;
    private final ModelToolDefinitionProjector toolDefinitionProjector;
    private final AgentEventPublisher eventPublisher;

    public AgentRunner(
            AgentModel model,
            ToolExecutor toolExecutor,
            ModelToolDefinitionProjector toolDefinitionProjector
    ) {
        this(model, toolExecutor, toolDefinitionProjector, NoopAgentEventPublisher.INSTANCE);
    }

    public AgentRunner(
            AgentModel model,
            ToolExecutor toolExecutor,
            ModelToolDefinitionProjector toolDefinitionProjector,
            AgentEventPublisher eventPublisher
    ) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
        this.toolDefinitionProjector = Objects.requireNonNull(
                toolDefinitionProjector,
                "toolDefinitionProjector must not be null"
        );
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
    }

    public AgentRunResult run(AgentRunSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        return run(
                spec,
                AgentRuntimeContext.minimal(spec.runId(), spec.sessionId()),
                RuntimeMiddlewareChain.empty()
        );
    }

    public AgentRunResult run(
            AgentRunSpec spec,
            AgentRuntimeContext runtimeContext,
            RuntimeMiddlewareChain middlewareChain
    ) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(middlewareChain, "middlewareChain must not be null");
        if (!spec.runId().equals(runtimeContext.runId())
                || !spec.sessionId().equals(runtimeContext.sessionId())) {
            throw new IllegalArgumentException("Runtime context identity must match AgentRunSpec");
        }
        AgentEventEmitter eventEmitter = new AgentEventEmitter(spec.runId(), eventPublisher);
        eventEmitter.emit(0, RunStartedEvent::new);
        List<AgentMessage> messages = new ArrayList<>(spec.messages());
        Set<String> toolsUsed = new LinkedHashSet<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();
        TokenUsage totalUsage = TokenUsage.ZERO;
        ToolPolicyContext policyContext = new ToolPolicyContext(
                spec.runId(),
                spec.sessionId(),
                spec.allowedTools(),
                spec.approvedToolCallIds()
        );

        for (int iteration = 1; iteration <= spec.maxIterations(); iteration++) {
            if (RuntimeCancellation.requested(runtimeContext, ExecutionCheckpoint.BEFORE_MODEL)) {
                return cancelled(
                        runtimeContext, iteration - 1, iteration,
                        toolsUsed, messages, totalUsage, eventEmitter
                );
            }
            int currentIteration = iteration;
            ModelInvocationState invocationState = new ModelInvocationState();
            ModelTurn turn;
            try {
                turn = middlewareChain.aroundModelCall(
                        runtimeContext,
                        new ModelCallMetadata(currentIteration, messages.size()),
                        () -> invokeModel(
                                messages,
                                eventEmitter,
                                currentIteration,
                                invocationState,
                                seenToolCallIds,
                                spec.allowedTools()
                        )
                );
            } catch (RuntimeMiddlewareFailureException exception) {
                TokenUsage usage = invocationState.completedTurn == null
                        ? totalUsage
                        : totalUsage.plus(invocationState.completedTurn.tokenUsage());
                int completedIterations = invocationState.started ? iteration : iteration - 1;
                return stopForMiddlewareFailure(
                        runtimeContext,
                        completedIterations,
                        toolsUsed,
                        messages,
                        usage,
                        eventEmitter,
                        iteration,
                        exception
                );
            } catch (RuntimeException exception) {
                AgentStopReason modelStopReason = modelStopReason(exception);
                RuntimeCancellation.sealCoreTerminal(runtimeContext);
                AgentRunResult result = stopped(
                        modelStopReason,
                        iteration,
                        toolsUsed,
                        messages,
                        totalUsage,
                        null,
                        null,
                        exception.getMessage()
                );
                eventEmitter.emit(
                        iteration,
                        metadata -> new RunStoppedEvent(
                                metadata,
                                modelStopReason,
                                null
                        )
                );
                return result;
            }

            totalUsage = totalUsage.plus(turn.tokenUsage());
            if (RuntimeCancellation.requested(runtimeContext, ExecutionCheckpoint.AFTER_MODEL)) {
                return cancelled(
                        runtimeContext, iteration, iteration,
                        toolsUsed, messages, totalUsage, eventEmitter
                );
            }
            if (turn.finishReason() == ModelFinishReason.STOP) {
                if (!RuntimeCancellation.trySealNormalCompletion(runtimeContext)) {
                    return cancelled(
                            runtimeContext, iteration, iteration,
                            toolsUsed, messages, totalUsage, eventEmitter
                    );
                }
                messages.add(AgentMessage.assistant(turn.content()));
                AgentRunResult result = new AgentRunResult(
                        turn.content(),
                        AgentStopReason.COMPLETED,
                        iteration,
                        List.copyOf(toolsUsed),
                        messages,
                        totalUsage,
                        null,
                        null,
                        null
                );
                eventEmitter.emit(iteration, RunCompletedEvent::new);
                return result;
            }
            if (turn.finishReason() == ModelFinishReason.LENGTH) {
                RuntimeCancellation.sealCoreTerminal(runtimeContext);
                messages.add(AgentMessage.assistant(turn.content()));
                AgentRunResult result = new AgentRunResult(
                        turn.content(),
                        AgentStopReason.MODEL_OUTPUT_LIMIT,
                        iteration,
                        List.copyOf(toolsUsed),
                        messages,
                        totalUsage,
                        null,
                        null,
                        "Model output limit reached"
                );
                eventEmitter.emit(
                        iteration,
                        metadata -> new RunStoppedEvent(
                                metadata,
                                AgentStopReason.MODEL_OUTPUT_LIMIT,
                                null
                        )
                );
                return result;
            }

            messages.add(AgentMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall toolCall : turn.toolCalls()) {
                eventEmitter.emit(
                        iteration,
                        metadata -> new ToolRequestedEvent(
                                metadata,
                                toolCall.id(),
                                toolCall.name()
                        )
                );
            }
            for (ToolCall toolCall : turn.toolCalls()) {
                ToolResult result;
                try {
                    result = toolExecutor.execute(
                            toolCall,
                            policyContext,
                            eventEmitter,
                            iteration,
                            runtimeContext,
                            middlewareChain
                    );
                } catch (RuntimeMiddlewareFailureException exception) {
                    return stopForMiddlewareFailure(
                            runtimeContext,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            eventEmitter,
                            iteration,
                            exception
                    );
                } catch (ExecutionCancelledException exception) {
                    return cancelled(
                            runtimeContext, iteration, iteration,
                            toolsUsed, messages, totalUsage, eventEmitter
                    );
                }
                if (result.policyBlocked()) {
                    RuntimeCancellation.sealCoreTerminal(runtimeContext);
                    AgentRunResult runResult = stopped(
                            AgentStopReason.POLICY_BLOCKED,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            null,
                            result.policyDecision(),
                            result.policyDecision().reason()
                    );
                    eventEmitter.emit(
                            iteration,
                            metadata -> new RunStoppedEvent(
                                    metadata,
                                    AgentStopReason.POLICY_BLOCKED,
                                    null
                            )
                    );
                    return runResult;
                }
                if (result.approvalRequired()) {
                    RuntimeCancellation.sealCoreTerminal(runtimeContext);
                    AgentRunResult runResult = stopped(
                            AgentStopReason.WAITING_APPROVAL,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            null,
                            result.policyDecision(),
                            result.policyDecision().reason()
                    );
                    eventEmitter.emit(iteration, RunWaitingApprovalEvent::new);
                    return runResult;
                }

                messages.add(AgentMessage.toolResult(
                        toolCall.id(), toolCall.name(), result.messageForModel()
                ));
                if (!result.success()) {
                    ToolErrorCode errorCode = result.error().code();
                    if (errorCode != ToolErrorCode.TOOL_NOT_FOUND) {
                        toolsUsed.add(toolCall.name());
                    }
                    RuntimeCancellation.sealCoreTerminal(runtimeContext);
                    AgentRunResult runResult = stopped(
                            AgentStopReason.TOOL_ERROR,
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            errorCode,
                            null,
                            result.error().message()
                    );
                    eventEmitter.emit(
                            iteration,
                            metadata -> new RunStoppedEvent(
                                    metadata,
                                    AgentStopReason.TOOL_ERROR,
                                    errorCode
                            )
                    );
                    return runResult;
                }
                toolsUsed.add(toolCall.name());
                if (RuntimeCancellation.requested(
                        runtimeContext, ExecutionCheckpoint.AFTER_TOOL_EXECUTION
                )) {
                    return cancelled(
                            runtimeContext, iteration, iteration,
                            toolsUsed, messages, totalUsage, eventEmitter
                    );
                }
            }
        }

        if (!RuntimeCancellation.trySealNormalCompletion(runtimeContext)) {
            return cancelled(
                    runtimeContext, spec.maxIterations(), spec.maxIterations(),
                    toolsUsed, messages, totalUsage, eventEmitter
            );
        }
        AgentRunResult result = stopped(
                AgentStopReason.MAX_ITERATIONS,
                spec.maxIterations(),
                toolsUsed,
                messages,
                totalUsage,
                null,
                null,
                "Maximum model iterations reached"
        );
        eventEmitter.emit(
                spec.maxIterations(),
                metadata -> new RunStoppedEvent(
                        metadata,
                        AgentStopReason.MAX_ITERATIONS,
                        null
                )
        );
        return result;
    }

    private ModelTurn invokeModel(
            List<AgentMessage> messages,
            AgentEventEmitter eventEmitter,
            int iteration,
            ModelInvocationState invocationState,
            Set<String> seenToolCallIds,
            Set<String> allowedTools
    ) {
        invocationState.started = true;
        eventEmitter.emit(iteration, ModelStartedEvent::new);
        try {
            ModelTurn turn = Objects.requireNonNull(
                    generateModelTurn(new AgentModelRequest(
                            messages, visibleToolDefinitions(allowedTools)
                    ), iteration),
                    "model returned a null turn"
            );
            validateUniqueToolCallIds(turn, seenToolCallIds);
            eventEmitter.emit(
                    iteration,
                    metadata -> new ModelCompletedEvent(
                            metadata,
                            turn.finishReason(),
                            turn.toolCalls().size(),
                            turn.tokenUsage().inputTokens(),
                            turn.tokenUsage().outputTokens(),
                            turn.tokenUsage().totalTokens(),
                            turn.tokenUsage().status()
                    )
            );
            invocationState.completedTurn = turn;
            return turn;
        } catch (RuntimeException exception) {
            AgentStopReason stopReason = modelStopReason(exception);
            eventEmitter.emit(
                    iteration,
                    metadata -> new ModelFailedEvent(metadata, stopReason)
            );
            throw exception;
        }
    }

    private ModelTurn generateModelTurn(AgentModelRequest request, int iteration) {
        return model instanceof GovernedAgentModel governed
                ? governed.generate(request, iteration)
                : model.generate(request);
    }

    private AgentStopReason modelStopReason(RuntimeException exception) {
        return exception instanceof ModelInvocationException invocation
                && invocation.failureKind() == ModelFailureKind.TIMEOUT
                ? AgentStopReason.MODEL_TIMEOUT
                : AgentStopReason.MODEL_ERROR;
    }

    private List<ModelToolDefinition> visibleToolDefinitions(Set<String> allowedTools) {
        return toolExecutor.toolDescriptors().stream()
                .filter(descriptor -> allowedTools.contains(descriptor.name()))
                .map(toolDefinitionProjector::project)
                .toList();
    }

    private void validateUniqueToolCallIds(ModelTurn turn, Set<String> seenToolCallIds) {
        Set<String> currentIds = new LinkedHashSet<>();
        for (ToolCall toolCall : turn.toolCalls()) {
            if (!currentIds.add(toolCall.id()) || seenToolCallIds.contains(toolCall.id())) {
                throw new IllegalArgumentException(
                        "Tool call id must be unique within a run: " + toolCall.id()
                );
            }
        }
        seenToolCallIds.addAll(currentIds);
    }

    private AgentRunResult stopForMiddlewareFailure(
            AgentRuntimeContext runtimeContext,
            int completedIterations,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            AgentEventEmitter eventEmitter,
            int eventIteration,
            RuntimeMiddlewareFailureException exception
    ) {
        RuntimeCancellation.sealCoreTerminal(runtimeContext);
        AgentRunResult result = stopped(
                AgentStopReason.INTERNAL_ERROR,
                completedIterations,
                toolsUsed,
                messages,
                tokenUsage,
                null,
                null,
                exception.getMessage()
        );
        eventEmitter.emit(
                eventIteration,
                metadata -> new RunStoppedEvent(
                        metadata,
                        AgentStopReason.INTERNAL_ERROR,
                        null
                )
        );
        return result;
    }

    private AgentRunResult cancelled(
            AgentRuntimeContext context,
            int completedIterations,
            int eventIteration,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            AgentEventEmitter eventEmitter
    ) {
        RuntimeCancellation.sealCoreTerminal(context);
        AgentRunResult result = stopped(
                AgentStopReason.CANCELLED,
                completedIterations,
                toolsUsed,
                messages,
                tokenUsage,
                null,
                null,
                "Execution cancelled"
        );
        eventEmitter.emit(
                eventIteration,
                metadata -> new RunStoppedEvent(metadata, AgentStopReason.CANCELLED, null)
        );
        return result;
    }

    private AgentRunResult stopped(
            AgentStopReason stopReason,
            int iterations,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            ToolErrorCode toolErrorCode,
            ToolPolicyDecision policyDecision,
            String errorMessage
    ) {
        return new AgentRunResult(
                "",
                stopReason,
                iterations,
                List.copyOf(toolsUsed),
                messages,
                tokenUsage,
                toolErrorCode,
                policyDecision,
                errorMessage
        );
    }

    private static final class ModelInvocationState {

        private boolean started;
        private ModelTurn completedTurn;
    }
}
