package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.BudgetBlockedEvent;
import com.multimodalAgent.agent.runtime.event.NoopAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.ToolFailedEvent;
import com.multimodalAgent.agent.runtime.event.ToolPolicyEvaluatedEvent;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.event.ToolValidatedEvent;
import com.multimodalAgent.agent.runtime.event.ToolValidationFailedEvent;
import com.multimodalAgent.agent.runtime.control.ExecutionCancelledException;
import com.multimodalAgent.agent.runtime.control.ExecutionCheckpoint;
import com.multimodalAgent.agent.runtime.control.RuntimeCancellation;
import com.multimodalAgent.agent.runtime.budget.BudgetBlock;
import com.multimodalAgent.agent.runtime.budget.BudgetBlockedException;
import com.multimodalAgent.agent.runtime.budget.BudgetSession;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver argumentResolver;
    private final ToolPolicyEngine policyEngine;
    private final ObjectMapper objectMapper;
    private final ToolOutcomeRecorder outcomeRecorder;
    private final ExecutorService executionScheduler;

    public ToolExecutor( // 工具执行器
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ToolPolicyEngine policyEngine,
            ObjectMapper objectMapper
    ) {
        this(
                toolRegistry,
                argumentResolver,
                policyEngine,
                objectMapper,
                ToolOutcomeRecorder.NOOP,
                ToolExecutionSchedulers.shared()
        );
    }

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ToolPolicyEngine policyEngine,
            ObjectMapper objectMapper,
            ToolOutcomeRecorder outcomeRecorder
    ) {
        this(
                toolRegistry,
                argumentResolver,
                policyEngine,
                objectMapper,
                outcomeRecorder,
                ToolExecutionSchedulers.shared()
        );
    }

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ToolPolicyEngine policyEngine,
            ObjectMapper objectMapper,
            ToolOutcomeRecorder outcomeRecorder,
            ExecutorService executionScheduler
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.argumentResolver = Objects.requireNonNull(argumentResolver, "argumentResolver must not be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null"); // JSON对象映射器
        this.outcomeRecorder = Objects.requireNonNull(
                outcomeRecorder,
                "outcomeRecorder must not be null"
        );
        this.executionScheduler = Objects.requireNonNull(
                executionScheduler,
                "executionScheduler must not be null"
        );
    }

    public ToolResult execute(ToolCall toolCall, ToolPolicyContext policyContext) {
        Objects.requireNonNull(policyContext, "policyContext must not be null");
        return execute(
                toolCall,
                policyContext,
                new AgentEventEmitter(policyContext.runId(), NoopAgentEventPublisher.INSTANCE),
                1,
                AgentRuntimeContext.minimal(policyContext.runId(), policyContext.sessionId()),
                RuntimeMiddlewareChain.empty(),
                new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
        );
    }

    public List<ToolDescriptor<?>> toolDescriptors() {
        return toolRegistry.descriptors();
    }

    public ToolResult execute(
            ToolCall toolCall,
            ToolPolicyContext policyContext,
            AgentEventEmitter eventEmitter,
            int iteration
    ) {
        return execute(
                toolCall,
                policyContext,
                eventEmitter,
                iteration,
                AgentRuntimeContext.minimal(policyContext.runId(), policyContext.sessionId()),
                RuntimeMiddlewareChain.empty(),
                new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
        );
    }

    public ToolResult execute(
            ToolCall toolCall,
            ToolPolicyContext policyContext,
            AgentEventEmitter eventEmitter,
            int iteration,
            AgentRuntimeContext runtimeContext,
            RuntimeMiddlewareChain middlewareChain
    ) {
        return execute(
                toolCall, policyContext, eventEmitter, iteration, runtimeContext,
                middlewareChain,
                new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
        );
    }

    public ToolResult execute(
            ToolCall toolCall,
            ToolPolicyContext policyContext,
            AgentEventEmitter eventEmitter,
            int iteration,
            AgentRuntimeContext runtimeContext,
            RuntimeMiddlewareChain middlewareChain,
            BudgetSession budgetSession
    ) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(policyContext, "policyContext must not be null");
        Objects.requireNonNull(eventEmitter, "eventEmitter must not be null");
        Objects.requireNonNull(runtimeContext, "runtimeContext must not be null");
        Objects.requireNonNull(middlewareChain, "middlewareChain must not be null");
        Objects.requireNonNull(budgetSession, "budgetSession must not be null");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        if (!policyContext.runId().equals(runtimeContext.runId())
                || !policyContext.sessionId().equals(runtimeContext.sessionId())) {
            throw new IllegalArgumentException("Runtime context identity must match policy context");
        }
        AgentTool<?, ?> tool = toolRegistry.find(toolCall.name()).orElse(null);
        if (tool == null) {
            emitToolFailed(eventEmitter, iteration, toolCall, ToolErrorCode.TOOL_NOT_FOUND);
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_FOUND,
                    "Unknown tool: " + toolCall.name()
            );
        }

        try {
            return executeTyped(
                    tool,
                    toolCall,
                    policyContext,
                    eventEmitter,
                    iteration,
                    runtimeContext,
                    middlewareChain,
                    budgetSession
            );
        } catch (ToolValidationException exception) {
            eventEmitter.emit(
                    iteration,
                    metadata -> new ToolValidationFailedEvent(
                            metadata,
                            toolCall.id(),
                            toolCall.name(),
                            ToolErrorCode.INVALID_ARGUMENTS
                    )
            );
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeMiddlewareFailureException exception) {
            throw exception;
        } catch (ExecutionCancelledException exception) {
            throw exception;
        } catch (BudgetBlockedException exception) {
            throw exception;
        } catch (ToolOutcomeSerializationException exception) {
            throw exception;
        } catch (ToolOutcomeRecordingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            emitToolFailed(eventEmitter, iteration, toolCall, ToolErrorCode.EXECUTION_FAILED);
            return ToolResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    "Tool execution failed: " + tool.name()
            );
        }
    }

    private <I, O> ToolResult executeTyped(
            AgentTool<I, O> tool,
            ToolCall toolCall,
            ToolPolicyContext policyContext,
            AgentEventEmitter eventEmitter,
            int iteration,
            AgentRuntimeContext runtimeContext,
            RuntimeMiddlewareChain middlewareChain,
            BudgetSession budgetSession
    ) {
        I input = argumentResolver.resolve(toolCall.arguments(), tool.descriptor().inputType());
        eventEmitter.emit(
                iteration,
                metadata -> new ToolValidatedEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name()
                )
        );
        ToolPolicyDecision decision;
        try {
            decision = Objects.requireNonNull(
                    policyEngine.evaluate(new ToolPolicyRequest(
                            toolCall,
                            tool.descriptor(),
                            input,
                            policyContext
                    )),
                    "policy engine returned a null decision"
            );
        } catch (RuntimeException exception) {
            decision = ToolPolicyDecision.deny("Tool policy evaluation failed");
        }
        ToolPolicyDecision evaluatedDecision = decision;
        eventEmitter.emit(
                iteration,
                metadata -> new ToolPolicyEvaluatedEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name(),
                        evaluatedDecision.type()
                )
        );

        if (decision.type() == ToolPolicyDecisionType.DENY) {
            return ToolResult.policyBlocked(decision);
        }
        if (decision.type() == ToolPolicyDecisionType.REQUIRE_APPROVAL) {
            return ToolResult.approvalRequired(decision);
        }

        if (RuntimeCancellation.requested(
                runtimeContext,
                ExecutionCheckpoint.BEFORE_TOOL_EXECUTION
        )) {
            throw new ExecutionCancelledException();
        }

        return middlewareChain.aroundToolExecution(
                runtimeContext,
                new ToolExecutionMetadata(
                        toolCall.id(),
                        toolCall.name(),
                        iteration,
                        tool.descriptor().recoveryContract().snapshot(toolCall.name())
                ),
                () -> executeAllowedTool(
                        tool,
                        input,
                        toolCall,
                        evaluatedDecision,
                        eventEmitter,
                        iteration,
                        budgetSession,
                        runtimeContext
                )
        );
    }

    private <I, O> ToolResult executeAllowedTool(
            AgentTool<I, O> tool,
            I input,
            ToolCall toolCall,
            ToolPolicyDecision decision,
            AgentEventEmitter eventEmitter,
            int iteration,
            BudgetSession budgetSession,
            AgentRuntimeContext runtimeContext
    ) {
        Optional<BudgetBlock> budgetBlock = budgetSession.admitToolCall();
        if (budgetBlock.isPresent()) {
            BudgetBlock block = budgetBlock.get();
            eventEmitter.emit(
                    iteration,
                    metadata -> BudgetBlockedEvent.forTool(
                            metadata, block, toolCall.id(), toolCall.name()
                    )
            );
            throw new BudgetBlockedException(block);
        }
        eventEmitter.emit(
                iteration,
                metadata -> new ToolStartedEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name()
                )
        );
        Future<O> execution = executionScheduler.submit(() -> invokeTool(
                tool,
                input,
                new ToolExecutionContext(
                        runtimeContext.runId(),
                        toolCall.id(),
                        runtimeContext.sessionId(),
                        Optional.ofNullable(runtimeContext.userId()),
                        Optional.ofNullable(runtimeContext.runtimeConfigSnapshotId())
                )
        ));
        O output;
        try {
            output = execution.get(
                    tool.descriptor().executionTimeout().toNanos(),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException exception) {
            execution.cancel(true);
            emitToolFailed(eventEmitter, iteration, toolCall, ToolErrorCode.TIMEOUT);
            return ToolResult.failure(
                    ToolErrorCode.TIMEOUT,
                    "Tool execution timed out: " + tool.name()
            );
        } catch (InterruptedException exception) {
            execution.cancel(true);
            Thread.currentThread().interrupt();
            throw new ExecutionCancelledException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Tool execution failed", cause);
        }
        String serializedOutput = serialize(output);
        String modelVisibleOutput = tool instanceof UntrustedEvidenceTool untrusted
                ? UntrustedEvidenceEnvelope.wrap(
                        objectMapper,
                        untrusted,
                        toolCall.name(),
                        serializedOutput
                )
                : serializedOutput;
        try {
            outcomeRecorder.recordSuccess(
                    runtimeContext.runId(),
                    toolCall.id(),
                    toolCall.name(),
                    modelVisibleOutput
            );
        } catch (RuntimeException exception) {
            throw new ToolOutcomeRecordingException(
                    "Exact tool outcome could not be made durable",
                    exception
            );
        }
        eventEmitter.emit(
                iteration,
                metadata -> new ToolSucceededEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name()
                )
        );
        return ToolResult.success(modelVisibleOutput, decision);
    }

    @SuppressWarnings("unchecked")
    private <I, O> O invokeTool(
            AgentTool<I, O> tool,
            I input,
            ToolExecutionContext context
    ) {
        if (tool instanceof ContextAwareAgentTool<?, ?> contextual) {
            return ((ContextAwareAgentTool<I, O>) contextual).execute(input, context);
        }
        return tool.execute(input);
    }

    private void emitToolFailed(
            AgentEventEmitter eventEmitter,
            int iteration,
            ToolCall toolCall,
            ToolErrorCode errorCode
    ) {
        eventEmitter.emit(
                iteration,
                metadata -> new ToolFailedEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name(),
                        errorCode
                )
        );
    }

    private String serialize(Object output) {
        if (output == null) {
            return "null";
        }
        if (output instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new ToolOutcomeSerializationException(
                    "Tool output could not be serialized after execution",
                    exception
            );
        }
    }
}
