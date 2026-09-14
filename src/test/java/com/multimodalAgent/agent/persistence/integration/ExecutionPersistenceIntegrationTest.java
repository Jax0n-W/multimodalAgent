package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyEngine;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p6-execution-persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaExecutionHistoryStore.class)
class ExecutionPersistenceIntegrationTest {

    @Autowired
    private JpaExecutionHistoryStore store;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Test
    void shouldPersistDirectSuccess() {
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.finalAnswer("final answer")),
                List.of(),
                new DefaultToolPolicyEngine()
        );

        AgentRunResult result = execute(harness, "direct", 3, Set.of(), Set.of());

        AgentRunEntity run = run("direct");
        List<AgentStepEntity> steps = steps("direct");
        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(AgentRunStatus.COMPLETED, run.getStatus());
        assertEquals(AgentStopReason.COMPLETED, run.getStopReason());
        assertEquals("final answer", run.getFinalContent());
        assertEquals(1, run.getCurrentIteration());
        assertNotNull(run.getStartedAt());
        assertNotNull(run.getCompletedAt());
        assertEquals(1, steps.size());
        assertEquals(AgentStepType.MODEL, steps.get(0).getStepType());
        assertEquals(AgentStepStatus.SUCCEEDED, steps.get(0).getStatus());
        assertEventTime(event(harness, AgentEventType.RUN_STARTED), run.getStartedAt());
        assertEventTime(event(harness, AgentEventType.RUN_COMPLETED), run.getCompletedAt());
        assertEventTime(event(harness, AgentEventType.MODEL_STARTED),
                steps.get(0).getStartedAt());
        assertEventTime(event(harness, AgentEventType.MODEL_COMPLETED),
                steps.get(0).getCompletedAt());
        assertTrue(executions("direct").isEmpty());
    }

    @Test
    void shouldPersistToolRoundTripWithStableCorrelationAndOrdering() {
        CountingTool tool = new CountingTool("knowledge_search", false, false);
        Harness harness = harness(
                new ScriptedAgentModel(
                        ModelTurn.toolCall(call("provider-call-1", tool.name())),
                        ModelTurn.finalAnswer("Redis Sentinel supports failover")
                ),
                List.of(tool),
                new DefaultToolPolicyEngine()
        );

        AgentRunResult result = execute(
                harness, "tool-success", 3, Set.of(tool.name()), Set.of()
        );

        List<AgentStepEntity> steps = steps("tool-success");
        ToolExecutionEntity execution = executions("tool-success").get(0);
        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, tool.executions());
        assertEquals(List.of(1, 2, 3), steps.stream().map(AgentStepEntity::getStepIndex).toList());
        assertEquals(List.of(AgentStepType.MODEL, AgentStepType.TOOL, AgentStepType.MODEL),
                steps.stream().map(AgentStepEntity::getStepType).toList());
        assertEquals("provider-call-1", execution.getToolCallId());
        assertEquals(tool.name(), execution.getToolName());
        assertEquals(ToolExecutionStatus.SUCCEEDED, execution.getStatus());
    }

    @Test
    void shouldPersistModelFailureWithoutToolFailure() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModel failingModel = request -> {
            modelCalls.incrementAndGet();
            throw new IllegalStateException("provider failed");
        };
        Harness harness = harness(failingModel, List.of(), new DefaultToolPolicyEngine());

        AgentRunResult result = execute(harness, "model-failure", 3, Set.of(), Set.of());

        AgentStepEntity step = steps("model-failure").get(0);
        assertEquals(1, modelCalls.get());
        assertEquals(AgentStopReason.MODEL_ERROR, result.stopReason());
        assertEquals(AgentRunStatus.FAILED, run("model-failure").getStatus());
        assertEquals(AgentStopReason.MODEL_ERROR, run("model-failure").getStopReason());
        assertEquals(AgentStepStatus.FAILED, step.getStatus());
        assertEquals("MODEL_ERROR", step.getErrorCode());
        assertTrue(executions("model-failure").isEmpty());
    }

    @Test
    void shouldPersistActualToolFailureSeparatelyFromModelFailure() {
        CountingTool tool = new CountingTool("failing_tool", true, false);
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.toolCall(call("call-failure", tool.name()))),
                List.of(tool),
                new DefaultToolPolicyEngine()
        );

        AgentRunResult result = execute(
                harness, "tool-failure", 3, Set.of(tool.name()), Set.of()
        );

        List<AgentStepEntity> steps = steps("tool-failure");
        ToolExecutionEntity execution = executions("tool-failure").get(0);
        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(1, tool.executions());
        assertEquals(AgentStepStatus.SUCCEEDED, steps.get(0).getStatus());
        assertEquals(AgentStepStatus.FAILED, steps.get(1).getStatus());
        assertEquals(ToolExecutionStatus.FAILED, execution.getStatus());
        assertEquals("EXECUTION_FAILED", execution.getErrorCode());
    }

    @Test
    void shouldPersistPolicyDenyWithoutClaimingActualExecution() {
        CountingTool tool = new CountingTool("denied_tool", false, false);
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.toolCall(call("call-denied", tool.name()))),
                List.of(tool),
                request -> ToolPolicyDecision.deny("not allowed")
        );

        AgentRunResult result = execute(
                harness, "policy-deny", 3, Set.of(tool.name()), Set.of()
        );

        AgentStepEntity toolStep = steps("policy-deny").get(1);
        ToolExecutionEntity execution = executions("policy-deny").get(0);
        assertEquals(AgentStopReason.POLICY_BLOCKED, result.stopReason());
        assertEquals(0, tool.executions());
        assertEquals(AgentStepStatus.SKIPPED, toolStep.getStatus());
        assertEquals(ToolExecutionStatus.BLOCKED, execution.getStatus());
        assertEquals("POLICY_DENIED", execution.getErrorCode());
        assertNull(toolStep.getStartedAt());
        assertNull(execution.getStartedAt());
    }

    @Test
    void shouldPersistWaitingApprovalWithoutClaimingExecution() {
        CountingTool tool = new CountingTool("approval_tool", false, true);
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.toolCall(call("call-approval", tool.name()))),
                List.of(tool),
                new DefaultToolPolicyEngine()
        );

        AgentRunResult result = execute(
                harness, "approval", 3, Set.of(tool.name()), Set.of()
        );

        AgentRunEntity run = run("approval");
        AgentStepEntity toolStep = steps("approval").get(1);
        ToolExecutionEntity execution = executions("approval").get(0);
        assertEquals(AgentStopReason.WAITING_APPROVAL, result.stopReason());
        assertEquals(AgentRunStatus.WAITING_APPROVAL, run.getStatus());
        assertEquals(0, tool.executions());
        assertEquals(AgentStepStatus.PLANNED, toolStep.getStatus());
        assertEquals(ToolExecutionStatus.PLANNED, execution.getStatus());
        assertNull(run.getCompletedAt());
    }

    @Test
    void shouldPersistMaxIterationsWithoutStartingAnExtraModelCall() {
        CountingTool tool = new CountingTool("loop_tool", false, false);
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.toolCall(call("loop-call-1", tool.name())),
                ModelTurn.toolCall(call("loop-call-2", tool.name()))
        );
        Harness harness = harness(model, List.of(tool), new DefaultToolPolicyEngine());

        AgentRunResult result = execute(
                harness, "max-iterations", 2, Set.of(tool.name()), Set.of()
        );

        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(2, model.requests().size());
        assertEquals(2, run("max-iterations").getCurrentIteration());
        assertEquals(2, tool.executions());
        assertEquals(4, steps("max-iterations").size());
    }

    @Test
    void shouldPersistMultiToolStepsInFrozenModelOrder() {
        CountingTool tool = new CountingTool("ordered_tool", false, false);
        Harness harness = harness(
                new ScriptedAgentModel(
                        ModelTurn.toolCall(
                                call("call-a", tool.name()),
                                call("call-b", tool.name()),
                                call("call-c", tool.name())
                        ),
                        ModelTurn.finalAnswer("done")
                ),
                List.of(tool),
                new DefaultToolPolicyEngine()
        );

        AgentRunResult result = execute(
                harness, "multi-tool", 3, Set.of(tool.name()), Set.of()
        );

        List<AgentStepEntity> steps = steps("multi-tool");
        List<ToolExecutionEntity> executions = executions("multi-tool");
        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(3, tool.executions());
        assertEquals(List.of(1, 2, 3, 4, 5),
                steps.stream().map(AgentStepEntity::getStepIndex).toList());
        assertEquals(List.of("call-a", "call-b", "call-c"),
                executions.stream().map(ToolExecutionEntity::getToolCallId).toList());
    }

    private Harness harness(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            ToolPolicyEngine policyEngine
    ) {
        ExecutionPersistenceFailureRegistry failures =
                new ExecutionPersistenceFailureRegistry();
        ExecutionPersistenceEventPublisher persistencePublisher =
                new ExecutionPersistenceEventPublisher(store, failures);
        RecordingAgentEventPublisher recordingPublisher = new RecordingAgentEventPublisher();
        AgentEventPublisher publisher = event -> {
            recordingPublisher.publish(event);
            persistencePublisher.publish(event);
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                policyEngine,
                objectMapper
        );
        RuntimeMiddlewareChain middleware = new RuntimeMiddlewareChain(List.of(
                new ExecutionPersistenceBoundaryMiddleware(failures)
        ));
        AgentRunner runner = new AgentRunner(
                model,
                executor,
                TestModelToolDefinitionProjector.INSTANCE,
                publisher
        );
        AgentExecutionCoordinator coordinator = new AgentExecutionCoordinator(runner, middleware);
        return new Harness(
                new PersistentAgentExecutionCoordinator(coordinator, store, failures),
                recordingPublisher
        );
    }

    private AgentRunResult execute(
            Harness harness,
            String suffix,
            int maxIterations,
            Set<String> allowedTools,
            Set<String> approvedToolCallIds
    ) {
        return harness.coordinator().execute(new AgentExecutionRequest(
                new AgentRunSpec(
                        runId(suffix),
                        "p6-session-" + suffix,
                        List.of(AgentMessage.user("test " + suffix)),
                        maxIterations,
                        allowedTools,
                        approvedToolCallIds
                ),
                "p6-request-" + suffix,
                6001L
        ));
    }

    private AgentRunEntity run(String suffix) {
        return runRepository.findByRunId(runId(suffix)).orElseThrow();
    }

    private List<AgentStepEntity> steps(String suffix) {
        return stepRepository.findByRunIdOrderByStepIndexAsc(runId(suffix));
    }

    private List<ToolExecutionEntity> executions(String suffix) {
        return toolExecutionRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId(suffix));
    }

    private String runId(String suffix) {
        return "p6-run-" + suffix;
    }

    private ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, Map.of("query", "Redis Sentinel"));
    }

    private AgentEvent event(Harness harness, AgentEventType type) {
        return harness.events().events().stream()
                .filter(event -> event.type() == type)
                .findFirst()
                .orElseThrow();
    }

    private void assertEventTime(AgentEvent event, Instant persistedTime) {
        assertTrue(
                Duration.between(event.occurredAt(), persistedTime).abs()
                        .compareTo(Duration.ofNanos(1_000)) <= 0,
                "persisted execution time must come from occurredAt at TIMESTAMP(6) precision"
        );
    }

    private record Harness(
            PersistentAgentExecutionCoordinator coordinator,
            RecordingAgentEventPublisher events
    ) {
    }

    private record ToolInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<ToolInput, String> {

        private final String name;
        private final boolean fail;
        private final boolean requiresApproval;
        private int executions;

        private CountingTool(String name, boolean fail, boolean requiresApproval) {
            this.name = name;
            this.fail = fail;
            this.requiresApproval = requiresApproval;
        }

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return new ToolDescriptor<>(
                    name,
                    "P6 deterministic test tool",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    requiresApproval
            );
        }

        @Override
        public String execute(ToolInput input) {
            executions++;
            if (fail) {
                throw new IllegalStateException("tool failed");
            }
            return "tool result";
        }

        private int executions() {
            return executions;
        }
    }
}
