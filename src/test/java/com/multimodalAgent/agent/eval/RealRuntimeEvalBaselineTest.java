package com.multimodalAgent.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelToolDefinitionProjector;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingAgentModelAdapter;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingOptions;
import com.multimodalAgent.agent.adapter.model.springai.streaming.SpringWebClientOpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
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
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.gateway.ModelGateway;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetry;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in P9.4 baseline against the already-running local Ollama endpoint. */
@Tag("eval-real")
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p9-real-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaExecutionConfigSnapshotStore.class)
class RealRuntimeEvalBaselineTest {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "mindbridge-qwen2.5-7b-ft:latest";

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private ToolExecutionRepository toolExecutionRepository;

    @Autowired
    private JpaExecutionConfigSnapshotStore snapshotStore;

    @Test
    void writesObservedRuntimeBaseline() throws Exception {
        String baseUrl = normalized(environmentOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL));
        String modelName = environmentOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        assumeOllamaReady(baseUrl, modelName);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvalSuite suite = new EvalDatasetLoader(objectMapper).loadRealModel();
        EvalExecutionTarget target = realRuntimeTarget(baseUrl, modelName, objectMapper);
        String gitSha = propertyOrEnvironment("eval.gitSha", "EVAL_GIT_SHA");
        EvalSourceTreeState sourceTreeState = EvalSourceTreeState.parse(
                propertyOrEnvironment("eval.sourceTreeState", "EVAL_SOURCE_TREE_STATE")
        );
        EvalRun run = new EvalRunner(Clock.systemUTC()).run(
                suite, target, gitSha, sourceTreeState
        );

        EvalReportFiles files = new EvalReportWriter(objectMapper).write(
                Path.of("target", "eval"), run
        );

        assertEquals(suite.cases().size(), run.records().size());
        assertTrue(java.nio.file.Files.isRegularFile(files.results()));
        assertTrue(java.nio.file.Files.isRegularFile(files.summary()));
        assertTrue(java.nio.file.Files.isRegularFile(files.markdown()));
    }

    private EvalExecutionTarget realRuntimeTarget(
            String baseUrl,
            String modelName,
            ObjectMapper objectMapper
    ) {
        ModelIdentity identity = new ModelIdentity("ollama", modelName);
        ModelTimeoutPolicy timeouts = new ModelTimeoutPolicy(
                Duration.ofMinutes(3), Duration.ofSeconds(45)
        );
        ResolvedModelConfig modelConfig = new ResolvedModelConfig(
                identity, BigDecimal.ZERO, 128, timeouts
        );
        StreamingModelInvocationScope invocationScope = new StreamingModelInvocationScope();
        AgentModel adapter = new OpenAiCompatibleStreamingAgentModelAdapter(
                new SpringWebClientOpenAiCompatibleStreamingClient(
                        baseUrl, "ollama-local", objectMapper
                ),
                new OpenAiCompatibleStreamingOptions("Ollama", modelName, 0.0, 128),
                objectMapper,
                invocationScope
        );
        List<ModelInvocationTelemetry> telemetry = new ArrayList<>();
        ModelGateway model = new ModelGateway(adapter, identity, timeouts, telemetry::add);
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        ToolExecutor tools = new ToolExecutor(
                new ToolRegistry(List.of(new SyntheticKnowledgeSearchTool())),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        JpaExecutionHistoryStore historyStore = new JpaExecutionHistoryStore(
                runRepository, stepRepository, toolExecutionRepository
        );
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        AgentRunner runner = new AgentRunner(
                model,
                tools,
                new SpringAiModelToolDefinitionProjector(objectMapper),
                event -> {
                    persistence.eventPublisher().publish(event);
                    events.publish(event);
                }
        );
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(
                runner, new RuntimeMiddlewareChain(List.of(
                        invocationScope, persistence.boundaryMiddleware()
                ))
        );
        SnapshottingAgentExecutionCoordinator snapshotting =
                new SnapshottingAgentExecutionCoordinator(
                        new ResolvedExecutionConfigResolver(modelConfig),
                        new ExecutionConfigSnapshotFactory(objectMapper),
                        snapshotStore,
                        persistence.persistentCoordinator(core)::execute
                );
        return new RuntimeEvalExecutionTarget("runtime-ollama", evalCase -> {
            events.clear();
            telemetry.clear();
            AgentRunSpec spec = new AgentRunSpec(
                    "eval-" + evalCase.caseId(),
                    "eval-" + evalCase.caseId(),
                    evalCase.messages(),
                    evalCase.execution().maxIterations(),
                    evalCase.execution().allowedTools(),
                    Set.of(),
                    budget(evalCase)
            );
            long started = System.nanoTime();
            AgentRunResult result = snapshotting.execute(new AgentExecutionRequest(
                    spec, "eval-request-" + evalCase.caseId(), 1L
            ));
            AgentRunEntity durableRun = runRepository.findByRunId(spec.runId()).orElseThrow();
            return new RuntimeEvalExecutionTarget.Capture(
                    result,
                    events.events(),
                    List.copyOf(telemetry),
                    durableRun.getRuntimeConfigSnapshotId() == null
                            ? EvalSnapshotObservation.missing()
                            : EvalSnapshotObservation.durableAgentRun(
                                    durableRun.getRuntimeConfigSnapshotId()
                            ),
                    null,
                    Duration.ofNanos(Math.max(0L, System.nanoTime() - started))
            );
        });
    }

    private ExecutionBudget budget(EvalCase evalCase) {
        ExecutionBudget.Builder builder = ExecutionBudget.builder();
        if (evalCase.execution().budget().maxModelCalls() != null) {
            builder.maxModelCalls(evalCase.execution().budget().maxModelCalls());
        }
        if (evalCase.execution().budget().maxToolCalls() != null) {
            builder.maxToolCalls(evalCase.execution().budget().maxToolCalls());
        }
        return builder.build();
    }

    private void assumeOllamaReady(String baseUrl, String modelName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assumeTrue(response.statusCode() == 200,
                    "Ollama preflight returned HTTP " + response.statusCode());
            JsonNode models = new ObjectMapper().readTree(response.body()).path("models");
            String withoutLatest = modelName.endsWith(":latest")
                    ? modelName.substring(0, modelName.length() - ":latest".length())
                    : modelName;
            boolean found = false;
            for (JsonNode model : models) {
                String registered = model.path("name").asText();
                if (registered.equals(modelName)
                        || registered.equals(withoutLatest)
                        || registered.equals(withoutLatest + ":latest")) {
                    found = true;
                    break;
                }
            }
            assumeTrue(found, "Ollama model is not registered: " + modelName);
        } catch (Exception exception) {
            assumeTrue(false, "Ollama preflight failed: " + exception.getMessage());
        }
    }

    private String propertyOrEnvironment(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalized(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record KnowledgeInput(@NotBlank String query) {
    }

    private static final class SyntheticKnowledgeSearchTool
            implements AgentTool<KnowledgeInput, Map<String, String>> {

        private static final ToolDescriptor<KnowledgeInput> DESCRIPTOR = new ToolDescriptor<>(
                "knowledge_search",
                "Search a synthetic campus resource dataset for evaluation only",
                KnowledgeInput.class,
                ToolRisk.LOW,
                true,
                true,
                false
        );

        @Override
        public ToolDescriptor<KnowledgeInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public Map<String, String> execute(KnowledgeInput input) {
            return Map.of(
                    "source", "synthetic-eval-dataset",
                    "query", input.query(),
                    "result", "Synthetic campus resource: weekdays 09:00-17:00, Building A."
            );
        }
    }

}
