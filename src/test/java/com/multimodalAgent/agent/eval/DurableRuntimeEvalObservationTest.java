package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.execution.config.SnapshottingAgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceComposition;
import com.multimodalAgent.agent.persistence.integration.JpaExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.persistence.integration.JpaExecutionHistoryStore;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p9-eval-durable;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaExecutionConfigSnapshotStore.class)
class DurableRuntimeEvalObservationTest {

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JpaExecutionConfigSnapshotStore snapshotStore;

    @Test
    void readsFormalSnapshotIdentityFromDurableAgentRunIntoEvalObservation() {
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        JpaExecutionHistoryStore historyStore = new JpaExecutionHistoryStore(
                runRepository, stepRepository, toolExecutionRepository
        );
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner agentRunner = new AgentRunner(
                new ScriptedAgentModel(ModelTurn.finalAnswer("done")),
                toolExecutor,
                TestModelToolDefinitionProjector.INSTANCE,
                event -> {
                    persistence.eventPublisher().publish(event);
                    events.publish(event);
                }
        );
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(
                agentRunner,
                new RuntimeMiddlewareChain(List.of(persistence.boundaryMiddleware()))
        );
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
                        snapshotStore,
                        persistence.persistentCoordinator(core)::execute
                );
        AgentRunSpec spec = new AgentRunSpec(
                "eval-durable-run", "eval-durable-session",
                List.of(AgentMessage.user("answer")), 2, Set.of(), Set.of(),
                ExecutionBudget.builder().maxModelCalls(1).maxToolCalls(0).build()
        );

        AgentRunResult result = snapshotting.execute(new AgentExecutionRequest(
                spec, "eval-durable-request", 1L
        ));
        AgentRunEntity durableRun = runRepository.findByRunId(spec.runId()).orElseThrow();
        EvalObservation observation = new RuntimeEvalExecutionTarget(
                "runtime",
                ignored -> new RuntimeEvalExecutionTarget.Capture(
                        result, events.events(), List.of(),
                        EvalSnapshotObservation.durableAgentRun(
                                durableRun.getRuntimeConfigSnapshotId()
                        ),
                        null, Duration.ZERO
                )
        ).execute(new EvalCase(
                "case", "1", "direct_answer", List.of(AgentMessage.user("answer")),
                new EvalExecutionConfig(2, Set.of(), new EvalExecutionBudget(1L, 0L)),
                new EvalOracle(result.stopReason(), Set.of(), Set.of(), 1L, 0L)
        ));

        assertNotNull(durableRun.getRuntimeConfigSnapshotId());
        assertEquals(durableRun.getRuntimeConfigSnapshotId(),
                observation.snapshotObservation().snapshotId());
        assertEquals(EvalSnapshotProvenance.DURABLE_AGENT_RUN,
                observation.snapshotObservation().provenance());
    }
}
