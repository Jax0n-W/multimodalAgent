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

public final class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver argumentResolver;
    private final ToolPolicyEngine policyEngine;
    private final ObjectMapper objectMapper;

    public ToolExecutor( // 工具执行器
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ToolPolicyEngine policyEngine,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.argumentResolver = Objects.requireNonNull(argumentResolver, "argumentResolver must not be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null"); // JSON对象映射器
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
                new ToolExecutionMetadata(toolCall.id(), toolCall.name(), iteration),
                () -> executeAllowedTool(
                        tool,
                        input,
                        toolCall,
                        evaluatedDecision,
                        eventEmitter,
                        iteration,
                        budgetSession
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
            BudgetSession budgetSession
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
        O output = tool.execute(input);
        String serializedOutput = serialize(output);
        eventEmitter.emit(
                iteration,
                metadata -> new ToolSucceededEvent(
                        metadata,
                        toolCall.id(),
                        toolCall.name()
                )
        );
        return ToolResult.success(serializedOutput, decision);
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
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool output could not be serialized", exception);
        }
    }
}
