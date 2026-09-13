package com.multimodalAgent.agent.runtime;

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
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
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
    private final AgentEventPublisher eventPublisher;

    public AgentRunner(AgentModel model, ToolExecutor toolExecutor) {
        this(model, toolExecutor, NoopAgentEventPublisher.INSTANCE);
    }

    public AgentRunner(
            AgentModel model,
            ToolExecutor toolExecutor,
            AgentEventPublisher eventPublisher
    ) {
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor must not be null");
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
        TokenUsage totalUsage = TokenUsage.ZERO;
        ToolPolicyContext policyContext = new ToolPolicyContext(
                spec.runId(),
                spec.sessionId(),
                spec.allowedTools(),
                spec.approvedToolCallIds()
        );

        for (int iteration = 1; iteration <= spec.maxIterations(); iteration++) {
            int currentIteration = iteration;
            ModelInvocationState invocationState = new ModelInvocationState();
            ModelTurn turn;
            try {
                turn = middlewareChain.aroundModelCall(
                        runtimeContext,
                        new ModelCallMetadata(currentIteration, messages.size()),
                        () -> invokeModel(messages, eventEmitter, currentIteration, invocationState)
                );
            } catch (RuntimeMiddlewareException exception) {
                TokenUsage usage = invocationState.completedTurn == null
                        ? totalUsage
                        : totalUsage.plus(invocationState.completedTurn.tokenUsage());
                int completedIterations = invocationState.started ? iteration : iteration - 1;
                return stopForMiddlewareFailure(
                        completedIterations,
                        toolsUsed,
                        messages,
                        usage,
                        eventEmitter,
                        iteration,
                        exception
                );
            } catch (RuntimeException exception) {
                AgentRunResult result = stopped(
                        AgentStopReason.MODEL_ERROR,
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
                                AgentStopReason.MODEL_ERROR,
                                null
                        )
                );
                return result;
            }

            totalUsage = totalUsage.plus(turn.tokenUsage());
            if (turn.finishReason() == ModelFinishReason.STOP) {
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
                } catch (RuntimeMiddlewareException exception) {
                    return stopForMiddlewareFailure(
                            iteration,
                            toolsUsed,
                            messages,
                            totalUsage,
                            eventEmitter,
                            iteration,
                            exception
                    );
                }
                if (result.policyBlocked()) {
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
            }
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
            ModelInvocationState invocationState
    ) {
        invocationState.started = true;
        eventEmitter.emit(iteration, ModelStartedEvent::new);
        try {
            ModelTurn turn = Objects.requireNonNull(
                    model.generate(List.copyOf(messages)),
                    "model returned a null turn"
            );
            eventEmitter.emit(
                    iteration,
                    metadata -> new ModelCompletedEvent(
                            metadata,
                            turn.finishReason(),
                            turn.toolCalls().size(),
                            turn.tokenUsage().inputTokens(),
                            turn.tokenUsage().outputTokens(),
                            turn.tokenUsage().totalTokens()
                    )
            );
            invocationState.completedTurn = turn;
            return turn;
        } catch (RuntimeException exception) {
            eventEmitter.emit(
                    iteration,
                    metadata -> new ModelFailedEvent(metadata, AgentStopReason.MODEL_ERROR)
            );
            throw exception;
        }
    }

    private AgentRunResult stopForMiddlewareFailure(
            int completedIterations,
            Set<String> toolsUsed,
            List<AgentMessage> messages,
            TokenUsage tokenUsage,
            AgentEventEmitter eventEmitter,
            int eventIteration,
            RuntimeMiddlewareException exception
    ) {
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
