package com.multimodalAgent.agent.runtime.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.control.ExecutionControl;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventEmitter;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.BudgetBlockedEvent;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.event.ToolPolicyEvaluatedEvent;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeInvocation;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddleware;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.ToolExecutionMetadata;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationException;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.trace.DecisionTrace;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BudgetGovernanceTest {

    @Test
    void unlimitedBudgetPreservesExistingToolLoop() {
        AtomicInteger toolCalls = new AtomicInteger();
        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(new TokenUsage(3, 2), call("call-1")),
                        answer("done", new TokenUsage(2, 1))),
                ExecutionBudget.unlimited(), toolCalls, ExecutionControl.create(), null
        );

        assertEquals(AgentStopReason.COMPLETED, run.result().stopReason());
        assertEquals(1, toolCalls.get());
        assertFalse(run.types().contains(AgentEventType.BUDGET_BLOCKED));
    }

    @Test
    void modelCallBudgetBlocksBeforeSecondModelStarted() {
        AtomicInteger providerCalls = new AtomicInteger();
        AgentModel model = request -> {
            providerCalls.incrementAndGet();
            return toolTurn(TokenUsage.ZERO, call("call-1"));
        };
        RunObservation run = execute(
                model, ExecutionBudget.builder().maxModelCalls(1).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );

        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, run.result().stopReason());
        assertEquals(1, providerCalls.get());
        assertEquals(1, run.usage().modelCalls());
        assertEquals(1, run.count(AgentEventType.MODEL_STARTED));
        BudgetBlockedEvent blocked = run.onlyBudgetBlock();
        assertEquals(BudgetDimension.MODEL_CALLS, blocked.dimension());
        assertEquals(List.of(AgentEventType.BUDGET_BLOCKED, AgentEventType.RUN_STOPPED),
                run.types().subList(run.types().size() - 2, run.types().size()));
    }

    @Test
    void admittedModelFailureConsumesCallWithoutRewritingFailure() {
        BudgetSession session = new BudgetSession(
                ExecutionBudget.builder().maxModelCalls(1).build(), Optional.empty()
        );
        assertTrue(session.admitModelCall().isEmpty());
        assertEquals(1, session.modelCalls());
        assertEquals(BudgetDimension.MODEL_CALLS,
                session.admitModelCall().orElseThrow().dimension());

        RunObservation run = execute(
                request -> { throw new IllegalStateException("provider failed"); },
                ExecutionBudget.builder().maxModelCalls(1).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.MODEL_ERROR, run.result().stopReason());
        assertEquals(1, run.usage().modelCalls());
        assertFalse(run.types().contains(AgentEventType.BUDGET_BLOCKED));

        RunObservation timeout = execute(
                request -> { throw new ModelInvocationException(
                        ModelFailureKind.TIMEOUT, "timed out", null
                ); },
                ExecutionBudget.builder().maxModelCalls(1).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.MODEL_TIMEOUT, timeout.result().stopReason());
        assertEquals(1, timeout.usage().modelCalls());
        assertFalse(timeout.types().contains(AgentEventType.BUDGET_BLOCKED));
    }

    @Test
    void modelMiddlewarePreFailureDoesNotConsumeExecutionBudget() {
        AtomicInteger providerCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                throw new IllegalStateException("model middleware pre failure");
            }
        };

        RunObservation run = execute(
                request -> {
                    providerCalls.incrementAndGet();
                    return answer("unexpected", TokenUsage.ZERO);
                },
                ExecutionBudget.builder().maxModelCalls(1).build(),
                new AtomicInteger(), ExecutionControl.create(), null,
                new RuntimeMiddlewareChain(List.of(middleware))
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, run.result().stopReason());
        assertEquals(0, providerCalls.get());
        assertEquals(0, run.usage().modelCalls());
        assertEquals(0, run.count(AgentEventType.MODEL_STARTED));
    }

    @Test
    void modelMiddlewarePostFailureKeepsCompletedFactsAndBudgetConsumption() {
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                next.proceed();
                throw new IllegalStateException("model middleware post failure");
            }
        };

        RunObservation run = execute(
                new ScriptedAgentModel(answer("done", new TokenUsage(3, 2))),
                ExecutionBudget.builder().maxModelCalls(1).maxTotalTokens(10).build(),
                new AtomicInteger(), ExecutionControl.create(), null,
                new RuntimeMiddlewareChain(List.of(middleware))
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, run.result().stopReason());
        assertEquals(1, run.usage().modelCalls());
        assertEquals(5, run.usage().totalTokens());
        assertEquals(1, run.count(AgentEventType.MODEL_STARTED));
        assertEquals(1, run.count(AgentEventType.MODEL_COMPLETED));
        assertEquals(0, run.count(AgentEventType.MODEL_FAILED));
    }

    @Test
    void allowedToolBudgetBlocksBeforeStartAndSideEffect() {
        AtomicInteger toolCalls = new AtomicInteger();
        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.ZERO, call("call-1"))),
                ExecutionBudget.builder().maxToolCalls(0).build(),
                toolCalls, ExecutionControl.create(), null
        );

        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, run.result().stopReason());
        assertEquals(0, toolCalls.get());
        assertEquals(0, run.usage().toolCalls());
        assertFalse(run.types().contains(AgentEventType.TOOL_STARTED));
        assertEquals(BudgetDimension.TOOL_CALLS, run.onlyBudgetBlock().dimension());
    }

    @Test
    void toolMiddlewarePreFailureDoesNotConsumeExecutionBudget() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                throw new IllegalStateException("tool middleware pre failure");
            }
        };

        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.ZERO, call("call-1"))),
                ExecutionBudget.builder().maxToolCalls(1).build(),
                effects, ExecutionControl.create(), null,
                new RuntimeMiddlewareChain(List.of(middleware))
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, run.result().stopReason());
        assertEquals(0, effects.get());
        assertEquals(0, run.usage().toolCalls());
        assertEquals(0, run.count(AgentEventType.TOOL_STARTED));
    }

    @Test
    void toolMiddlewarePostFailureKeepsSucceededFactsAndBudgetConsumption() {
        AtomicInteger effects = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                next.proceed();
                throw new IllegalStateException("tool middleware post failure");
            }
        };

        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.ZERO, call("call-1"))),
                ExecutionBudget.builder().maxToolCalls(1).build(),
                effects, ExecutionControl.create(), null,
                new RuntimeMiddlewareChain(List.of(middleware))
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, run.result().stopReason());
        assertEquals(1, effects.get());
        assertEquals(1, run.usage().toolCalls());
        assertEquals(1, run.count(AgentEventType.TOOL_STARTED));
        assertEquals(1, run.count(AgentEventType.TOOL_SUCCEEDED));
        assertEquals(0, run.count(AgentEventType.TOOL_FAILED));
    }

    @Test
    void deniedAndApprovalToolsDoNotConsumeExecutionBudget() {
        BudgetSession deniedSession = sessionWithNoToolCalls();
        ToolResult denied = executeTool(
                tool(false, new AtomicInteger()),
                new ToolPolicyContext("run", "session", Set.of(), Set.of()),
                deniedSession
        );
        assertTrue(denied.policyBlocked());
        assertEquals(0, deniedSession.toolCalls());

        BudgetSession approvalSession = sessionWithNoToolCalls();
        ToolResult approval = executeTool(
                tool(true, new AtomicInteger()),
                new ToolPolicyContext("run", "session", Set.of("knowledge_search"), Set.of()),
                approvalSession
        );
        assertTrue(approval.approvalRequired());
        assertEquals(0, approvalSession.toolCalls());
    }

    @Test
    void successfulToolConsumesExactlyOneAdmission() {
        BudgetSession session = new BudgetSession(
                ExecutionBudget.builder().maxToolCalls(1).build(), Optional.empty()
        );
        AtomicInteger effects = new AtomicInteger();
        ToolResult result = executeTool(
                tool(false, effects),
                new ToolPolicyContext(
                        "run", "session", Set.of("knowledge_search"), Set.of()
                ),
                session
        );
        assertTrue(result.success());
        assertEquals(1, session.toolCalls());
        assertEquals(1, effects.get());
    }

    @Test
    void validationFailureDoesNotConsumeAndStartedFailureDoesConsumeToolBudget() {
        BudgetSession validationSession = sessionWithNoToolCalls();
        ToolResult invalid = executeTool(
                tool(false, new AtomicInteger()),
                new ToolPolicyContext(
                        "run", "session", Set.of("knowledge_search"), Set.of()
                ),
                validationSession,
                new ToolCall("call-invalid", "knowledge_search", Map.of("query", ""))
        );
        assertFalse(invalid.success());
        assertEquals(0, validationSession.toolCalls());

        BudgetSession failureSession = new BudgetSession(
                ExecutionBudget.builder().maxToolCalls(1).build(), Optional.empty()
        );
        AtomicInteger effects = new AtomicInteger();
        ToolResult failed = executeTool(
                failingTool(effects),
                new ToolPolicyContext(
                        "run", "session", Set.of("knowledge_search"), Set.of()
                ),
                failureSession
        );
        assertFalse(failed.success());
        assertEquals(1, failureSession.toolCalls());
        assertEquals(1, effects.get());
    }

    @Test
    void multipleToolCallsCannotExceedBudget() {
        AtomicInteger effects = new AtomicInteger();
        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(
                        TokenUsage.ZERO, call("call-1"), call("call-2")
                )),
                ExecutionBudget.builder().maxToolCalls(1).build(),
                effects, ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, run.result().stopReason());
        assertEquals(1, effects.get());
        assertEquals(1, run.count(AgentEventType.TOOL_STARTED));
        assertEquals("call-2", run.onlyBudgetBlock().toolCallId().orElseThrow());
    }

    @Test
    void knownUsageUnderLimitAllowsFutureWork() {
        RunObservation run = execute(
                new ScriptedAgentModel(
                        toolTurn(new TokenUsage(3, 2), call("call-1")),
                        answer("done", new TokenUsage(1, 1))
                ),
                ExecutionBudget.builder().maxTotalTokens(6).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.COMPLETED, run.result().stopReason());
        assertEquals(2, run.count(AgentEventType.MODEL_STARTED));
    }

    @Test
    void confirmedTokenLimitBlocksFutureWork() {
        AtomicInteger effects = new AtomicInteger();
        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(
                        new TokenUsage(3, 2), call("call-1")
                )),
                ExecutionBudget.builder().maxTotalTokens(5).build(),
                effects, ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, run.result().stopReason());
        assertEquals(BudgetDimension.TOTAL_TOKENS, run.onlyBudgetBlock().dimension());
        assertEquals(0, effects.get());
    }

    @Test
    void inputAndOutputTokenDimensionsAreAccountedIndependently() {
        BudgetSession input = new BudgetSession(
                ExecutionBudget.builder().maxInputTokens(3).build(), Optional.empty()
        );
        input.account(new TokenUsage(3, 1));
        assertEquals(BudgetDimension.INPUT_TOKENS,
                input.admitToolCall().orElseThrow().dimension());

        BudgetSession output = new BudgetSession(
                ExecutionBudget.builder().maxOutputTokens(2).build(), Optional.empty()
        );
        output.account(new TokenUsage(1, 2));
        assertEquals(BudgetDimension.OUTPUT_TOKENS,
                output.admitModelCall().orElseThrow().dimension());
    }

    @Test
    void unknownUsageWithTokenBudgetFailsClosedOnlyWhenFutureWorkExists() {
        RunObservation requiringFutureWork = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.UNKNOWN, call("call-1"))),
                ExecutionBudget.builder().maxTotalTokens(100).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.BUDGET_UNVERIFIABLE,
                requiringFutureWork.result().stopReason());
        assertEquals(BudgetBlockReason.UNVERIFIABLE,
                requiringFutureWork.onlyBudgetBlock().reason());

        RunObservation terminal = execute(
                new ScriptedAgentModel(answer("done", TokenUsage.UNKNOWN)),
                ExecutionBudget.builder().maxTotalTokens(100).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.COMPLETED, terminal.result().stopReason());
    }

    @Test
    void unknownUsageWithoutTokenBudgetKeepsExistingBehavior() {
        RunObservation run = execute(
                new ScriptedAgentModel(
                        toolTurn(TokenUsage.UNKNOWN, call("call-1")),
                        answer("done", TokenUsage.UNKNOWN)
                ),
                ExecutionBudget.unlimited(), new AtomicInteger(),
                ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.COMPLETED, run.result().stopReason());
    }

    @Test
    void stopAndLengthTerminalTruthAreNotRewrittenByTokenBudget() {
        ExecutionBudget budget = ExecutionBudget.builder().maxTotalTokens(1).build();
        RunObservation stop = execute(
                new ScriptedAgentModel(answer("done", new TokenUsage(10, 10))),
                budget, new AtomicInteger(), ExecutionControl.create(), null
        );
        RunObservation length = execute(
                new ScriptedAgentModel(new ModelTurn(
                        ModelFinishReason.LENGTH, "partial", List.of(),
                        new TokenUsage(10, 10)
                )),
                budget, new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.COMPLETED, stop.result().stopReason());
        assertEquals(AgentStopReason.MODEL_OUTPUT_LIMIT, length.result().stopReason());
    }

    @Test
    void explicitPricingProducesDeterministicBigDecimalCost() {
        ModelIdentity identity = new ModelIdentity("provider", "model");
        ModelPricing pricing = new ModelPricing(
                identity, new BigDecimal("2.00"), new BigDecimal("6.00")
        );
        BudgetSession session = new BudgetSession(
                ExecutionBudget.builder().maxCost(new BigDecimal("1.00"))
                        .pricing(pricing).build(),
                Optional.of(identity)
        );
        assertTrue(session.admitModelCall().isEmpty());
        session.account(new TokenUsage(250_000, 50_000));
        assertEquals(0, new BigDecimal("0.80000000")
                .compareTo(session.cost().orElseThrow()));
        assertTrue(new BudgetSession(ExecutionBudget.unlimited(), Optional.empty())
                .cost().isEmpty());

        session.account(new TokenUsage(100_000, 0));
        BudgetBlock block = session.admitToolCall().orElseThrow();
        assertEquals(BudgetDimension.COST, block.dimension());
        assertEquals(BudgetBlockReason.EXHAUSTED, block.reason());
    }

    @Test
    void costBudgetWithoutPricingFailsClosedBeforeProviderCall() {
        AtomicInteger providerCalls = new AtomicInteger();
        RunObservation run = execute(
                request -> {
                    providerCalls.incrementAndGet();
                    return ModelTurn.finalAnswer("unexpected");
                },
                ExecutionBudget.builder().maxCost(BigDecimal.ONE).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        assertEquals(AgentStopReason.BUDGET_UNVERIFIABLE, run.result().stopReason());
        assertEquals(0, providerCalls.get());
        assertEquals(0, run.count(AgentEventType.MODEL_STARTED));
    }

    @Test
    void cancellationWinsAtModelAndToolAdmissionBoundaries() {
        ExecutionControl beforeModel = ExecutionControl.create();
        beforeModel.requestCancel();
        RunObservation modelBoundary = execute(
                request -> ModelTurn.finalAnswer("unexpected"),
                ExecutionBudget.builder().maxModelCalls(0).build(),
                new AtomicInteger(), beforeModel, null
        );
        assertEquals(AgentStopReason.CANCELLED, modelBoundary.result().stopReason());
        assertEquals(0, modelBoundary.usage().modelCalls());
        assertFalse(modelBoundary.types().contains(AgentEventType.BUDGET_BLOCKED));

        ExecutionControl beforeTool = ExecutionControl.create();
        AtomicInteger effects = new AtomicInteger();
        RunObservation toolBoundary = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.ZERO, call("call-1"))),
                ExecutionBudget.builder().maxToolCalls(0).build(),
                effects,
                beforeTool,
                event -> {
                    if (event instanceof ToolPolicyEvaluatedEvent evaluated
                            && evaluated.decision() == ToolPolicyDecisionType.ALLOW) {
                        beforeTool.requestCancel();
                    }
                }
        );
        assertEquals(AgentStopReason.CANCELLED, toolBoundary.result().stopReason());
        assertEquals(0, effects.get());
        assertEquals(0, toolBoundary.usage().toolCalls());
        assertFalse(toolBoundary.types().contains(AgentEventType.BUDGET_BLOCKED));
    }

    @Test
    void budgetTerminalEventBuildsValidDecisionTrace() {
        RunObservation run = execute(
                new ScriptedAgentModel(toolTurn(TokenUsage.ZERO, call("call-1"))),
                ExecutionBudget.builder().maxToolCalls(0).build(),
                new AtomicInteger(), ExecutionControl.create(), null
        );
        DecisionTrace trace = new DecisionTraceBuilder().build(run.events());
        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, trace.stopReason());
        assertEquals(1, trace.orderedToolDecisions().size());
        assertEquals("BLOCKED", trace.orderedToolDecisions().get(0).executionOutcome().name());
        assertEquals("BUDGET", trace.errors().get(0).source().name());

        List<AgentEvent> mismatched = new ArrayList<>(run.events());
        RunStoppedEvent terminal = assertInstanceOf(
                RunStoppedEvent.class, mismatched.get(mismatched.size() - 1)
        );
        mismatched.set(
                mismatched.size() - 1,
                new RunStoppedEvent(
                        terminal.metadata(), AgentStopReason.BUDGET_UNVERIFIABLE, null
                )
        );
        assertThrows(IllegalArgumentException.class,
                () -> new DecisionTraceBuilder().build(mismatched));
    }

    private RunObservation execute(
            AgentModel model,
            ExecutionBudget budget,
            AtomicInteger toolEffects,
            ExecutionControl control,
            Consumer<AgentEvent> observer
    ) {
        return execute(
                model, budget, toolEffects, control, observer,
                RuntimeMiddlewareChain.empty()
        );
    }

    private RunObservation execute(
            AgentModel model,
            ExecutionBudget budget,
            AtomicInteger toolEffects,
            ExecutionControl control,
            Consumer<AgentEvent> observer,
            RuntimeMiddlewareChain middlewareChain
    ) {
        List<AgentEvent> events = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tool(false, toolEffects))),
                new ToolArgumentResolver(
                        mapper, Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(), mapper
        );
        AgentRunner runner = new AgentRunner(
                model, executor, TestModelToolDefinitionProjector.INSTANCE,
                event -> {
                    events.add(event);
                    if (observer != null) {
                        observer.accept(event);
                    }
                }
        );
        AgentRunSpec spec = new AgentRunSpec(
                "budget-run", "budget-session",
                List.of(AgentMessage.user("question")), 3,
                Set.of("knowledge_search"), Set.of(), budget
        );
        AgentRuntimeContext context = new AgentRuntimeContext(
                spec.runId(), null, spec.sessionId(), null, null,
                control, new RuntimeAttributes()
        );
        AgentRunResult result = runner.run(spec, context, middlewareChain);
        BudgetUsage usage = context.attributes()
                .get(BudgetRuntimeAttributes.USAGE)
                .orElseThrow();
        return new RunObservation(result, List.copyOf(events), usage);
    }

    private ToolResult executeTool(
            AgentTool<KnowledgeSearchInput, String> tool,
            ToolPolicyContext policyContext,
            BudgetSession budgetSession
    ) {
        return executeTool(tool, policyContext, budgetSession, call("call-1"));
    }

    private ToolResult executeTool(
            AgentTool<KnowledgeSearchInput, String> tool,
            ToolPolicyContext policyContext,
            BudgetSession budgetSession,
            ToolCall toolCall
    ) {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolArgumentResolver(
                        mapper, Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(), mapper
        );
        return executor.execute(
                toolCall, policyContext,
                new AgentEventEmitter(policyContext.runId(), event -> { }), 1,
                AgentRuntimeContext.minimal(policyContext.runId(), policyContext.sessionId()),
                RuntimeMiddlewareChain.empty(), budgetSession
        );
    }

    private BudgetSession sessionWithNoToolCalls() {
        return new BudgetSession(
                ExecutionBudget.builder().maxToolCalls(0).build(), Optional.empty()
        );
    }

    private AgentTool<KnowledgeSearchInput, String> tool(
            boolean requiresApproval,
            AtomicInteger effects
    ) {
        return new AgentTool<>() {
            private final ToolDescriptor<KnowledgeSearchInput> descriptor =
                    new ToolDescriptor<>(
                            "knowledge_search", "test tool", KnowledgeSearchInput.class,
                            ToolRisk.LOW, true, true, requiresApproval
                    );

            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return descriptor;
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                effects.incrementAndGet();
                return "result";
            }
        };
    }

    private AgentTool<KnowledgeSearchInput, String> failingTool(AtomicInteger effects) {
        return new AgentTool<>() {
            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return new ToolDescriptor<>(
                        "knowledge_search", "test tool", KnowledgeSearchInput.class,
                        ToolRisk.LOW, true, true, false
                );
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                effects.incrementAndGet();
                throw new IllegalStateException("tool failed");
            }
        };
    }

    private ToolCall call(String id) {
        return new ToolCall(id, "knowledge_search", Map.of("query", "question"));
    }

    private ModelTurn toolTurn(TokenUsage usage, ToolCall... calls) {
        return new ModelTurn(ModelFinishReason.TOOL_CALLS, "", List.of(calls), usage);
    }

    private ModelTurn answer(String content, TokenUsage usage) {
        return new ModelTurn(ModelFinishReason.STOP, content, List.of(), usage);
    }

    private record RunObservation(
            AgentRunResult result,
            List<AgentEvent> events,
            BudgetUsage usage
    ) {

        private List<AgentEventType> types() {
            return events.stream().map(AgentEvent::type).toList();
        }

        private long count(AgentEventType type) {
            return events.stream().filter(event -> event.type() == type).count();
        }

        private BudgetBlockedEvent onlyBudgetBlock() {
            return assertInstanceOf(
                    BudgetBlockedEvent.class,
                    events.stream()
                            .filter(event -> event.type() == AgentEventType.BUDGET_BLOCKED)
                            .findFirst()
                            .orElseThrow()
            );
        }
    }
}
