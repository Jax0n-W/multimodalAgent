package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.execution.config.SnapshottingAgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEvalExecutionTargetTest {

    @Test
    void observesExistingCoordinatorEventsAndMarksInterceptedSnapshotNonDurable() {
        EvalCase evalCase = new EvalCase(
                "runtime-001", "1", "direct_answer",
                List.of(AgentMessage.user("answer directly")),
                new EvalExecutionConfig(2, Set.of(), new EvalExecutionBudget(1L, 0L)),
                new EvalOracle(AgentStopReason.COMPLETED, Set.of(), Set.of(), 1L, 0L)
        );
        RuntimeEvalExecutionTarget target = new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> executeExistingRuntime(evalCase)
        );

        EvalRecord record = new EvalRecordFactory().create(evalCase, target.execute(evalCase));

        assertTrue(record.contractPass());
        assertEquals(AgentStopReason.COMPLETED, record.stopReason());
        assertEquals(1, record.modelCalls());
        assertEquals(0, record.toolCalls());
        assertNotNull(record.runtimeConfigSnapshotId());
        assertEquals(EvalSnapshotProvenance.NON_DURABLE,
                record.runtimeConfigSnapshotProvenance());
    }

    @Test
    void deterministicScriptedLengthTurnProducesOutputLimitContractFact() {
        EvalCase evalCase = new EvalCase(
                "length-001", "1", "output_limit",
                List.of(AgentMessage.user("emit deterministic length")),
                new EvalExecutionConfig(2, Set.of(), new EvalExecutionBudget(1L, 0L)),
                new EvalOracle(AgentStopReason.MODEL_OUTPUT_LIMIT,
                        Set.of(), Set.of(), 1L, 0L)
        );

        EvalRecord record = new EvalRecordFactory().create(
                evalCase,
                new RuntimeEvalExecutionTarget(
                        "runtime",
                        ignored -> executeExistingRuntime(
                                evalCase, ModelTurn.outputLimit("partial")
                        )
                ).execute(evalCase)
        );

        assertTrue(record.contractPass());
        assertEquals(AgentStopReason.MODEL_OUTPUT_LIMIT, record.stopReason());
        assertEquals(1, record.modelCalls());
    }

    private RuntimeEvalExecutionTarget.Capture executeExistingRuntime(EvalCase evalCase) {
        return executeExistingRuntime(evalCase, ModelTurn.finalAnswer("done"));
    }

    private RuntimeEvalExecutionTarget.Capture executeExistingRuntime(
            EvalCase evalCase,
            ModelTurn turn
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                new ScriptedAgentModel(turn),
                toolExecutor,
                TestModelToolDefinitionProjector.INSTANCE,
                events
        );
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(
                runner, RuntimeMiddlewareChain.empty()
        );
        AtomicReference<ExecutionConfigSnapshot> persisted = new AtomicReference<>();
        AtomicReference<String> delegatedSnapshotId = new AtomicReference<>();
        ExecutionConfigSnapshotStore store = new ExecutionConfigSnapshotStore() {
            @Override
            public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
                persisted.compareAndSet(null, snapshot);
                return persisted.get();
            }

            @Override
            public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
                return Optional.ofNullable(persisted.get())
                        .filter(snapshot -> snapshot.snapshotId().equals(snapshotId));
            }
        };
        SnapshottingAgentExecutionCoordinator snapshotting =
                new SnapshottingAgentExecutionCoordinator(
                        new ResolvedExecutionConfigResolver(new ResolvedModelConfig(
                                new ModelIdentity("fixture", "scripted"),
                                BigDecimal.ZERO,
                                128,
                                new ModelTimeoutPolicy(
                                        Duration.ofSeconds(5), Duration.ofSeconds(2)
                                )
                        )),
                        new ExecutionConfigSnapshotFactory(objectMapper),
                        store,
                        request -> {
                            delegatedSnapshotId.set(request.runtimeConfigSnapshotId());
                            return core.execute(request);
                        }
                );
        AgentRunSpec spec = new AgentRunSpec(
                "eval-" + evalCase.caseId(),
                "eval-suite",
                evalCase.messages(),
                evalCase.execution().maxIterations(),
                evalCase.execution().allowedTools(),
                Set.of()
        );
        long started = System.nanoTime();
        AgentRunResult result = snapshotting.execute(new AgentExecutionRequest(
                spec, "request-" + evalCase.caseId(), 1L
        ));

        assertTrue(events.events().stream().anyMatch(ModelStartedEvent.class::isInstance));
        return new RuntimeEvalExecutionTarget.Capture(
                result,
                events.events(),
                List.of(),
                EvalSnapshotObservation.nonDurable(delegatedSnapshotId.get()),
                null,
                Duration.ofNanos(System.nanoTime() - started)
        );
    }
}
