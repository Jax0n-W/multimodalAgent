package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.BudgetDimension;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.BudgetBlockedEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in P5V verification against the already-running local Ollama instance.
 *
 * <p>The Spring AI OpenAI protocol client is deliberately pointed at Ollama's local compatible
 * endpoint. Spring AI 1.0.0's native Ollama response mapping discards provider tool-call IDs,
 * while the compatible endpoint preserves the ID required by the runtime contract.</p>
 */
@Tag("real-model")
class RealModelAgentSmokeTest {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "mindbridge-qwen2.5-7b-ft";
    private static final List<AgentEventType> TOOL_ROUND_TRIP_EVENTS = List.of(
            AgentEventType.RUN_STARTED,
            AgentEventType.MODEL_STARTED,
            AgentEventType.MODEL_COMPLETED,
            AgentEventType.TOOL_REQUESTED,
            AgentEventType.TOOL_VALIDATED,
            AgentEventType.TOOL_POLICY_EVALUATED,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_SUCCEEDED,
            AgentEventType.MODEL_STARTED,
            AgentEventType.MODEL_COMPLETED,
            AgentEventType.RUN_COMPLETED
    );

    @Test
    void shouldCompleteDirectAnswerThroughCoordinator() {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        SmokeRuntime runtime = runtime(List.of(), publisher);

        AgentRunResult result = runtime.coordinator().execute(request(
                "p5v-ollama-direct",
                List.of(
                        AgentMessage.system(
                                "Answer directly without calling a tool. Reply with one short sentence."
                        ),
                        AgentMessage.user("What is two plus two?")
                ),
                Set.of()
        ));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, result.iterations());
        assertFalse(result.finalContent().isBlank());
        assertEquals(1, runtime.model().requests().size());
        assertEquals(ModelFinishReason.STOP, runtime.model().turns().get(0).finishReason());
        assertFalse(publisher.events().stream().anyMatch(event ->
                event.type() == AgentEventType.TOOL_REQUESTED
                        || event.type() == AgentEventType.TOOL_STARTED));
        assertEquals(AgentEventType.RUN_COMPLETED,
                publisher.events().get(publisher.events().size() - 1).type());
        new DecisionTraceBuilder().build(publisher.events());

        System.out.printf(
                "P5V_REAL_DIRECT modelCalls=%d stopReason=%s final=%s events=%s%n",
                runtime.model().requests().size(),
                result.stopReason(),
                oneLine(result.finalContent()),
                eventTypes(publisher)
        );
    }

    @Test
    void shouldCompleteRealToolCallRoundTripThroughCoordinator() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        SmokeRuntime runtime = runtime(List.of(tool), publisher);

        AgentRunResult result = runtime.coordinator().execute(request(
                "p5v-ollama-tool",
                List.of(
                        AgentMessage.system(
                                "You are following a strict two-step tool protocol. "
                                        + "If the conversation has no knowledge_search result, call "
                                        + "knowledge_search exactly once with query Redis Sentinel and do "
                                        + "not answer yet. If a knowledge_search result is present, do not "
                                        + "call any tool and answer only from that result in two short sentences."
                        ),
                        AgentMessage.user(
                                "Use knowledge_search to find out what Redis Sentinel does."
                        )
                ),
                Set.of("knowledge_search")
        ));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(2, result.iterations());
        assertFalse(result.finalContent().isBlank());
        assertEquals(1, tool.executions());
        assertEquals("Redis Sentinel", tool.lastQuery());
        assertEquals(2, runtime.model().requests().size());
        assertEquals(2, runtime.model().turns().size());

        ModelTurn firstTurn = runtime.model().turns().get(0);
        assertEquals(ModelFinishReason.TOOL_CALLS, firstTurn.finishReason());
        assertEquals(1, firstTurn.toolCalls().size());
        ToolCall providerCall = firstTurn.toolCalls().get(0);
        assertEquals("knowledge_search", providerCall.name());
        assertFalse(providerCall.id().isBlank());

        AgentModelRequest secondRequest = runtime.model().requests().get(1);
        AgentMessage assistantCall = secondRequest.messages().stream()
                .filter(message -> message.role() == AgentMessageRole.ASSISTANT)
                .filter(message -> !message.toolCalls().isEmpty())
                .findFirst()
                .orElseThrow();
        AgentMessage toolResult = secondRequest.messages().stream()
                .filter(message -> message.role() == AgentMessageRole.TOOL)
                .findFirst()
                .orElseThrow();
        assertEquals(providerCall.id(), assistantCall.toolCalls().get(0).id());
        assertEquals(providerCall.id(), toolResult.toolCallId());
        assertEquals("knowledge_search", toolResult.toolName());
        assertTrue(toolResult.content().contains("automatic failover"));

        assertEquals(ModelFinishReason.STOP, runtime.model().turns().get(1).finishReason());
        assertEquals(TOOL_ROUND_TRIP_EVENTS, eventTypes(publisher));
        assertFalse(publisher.events().stream().anyMatch(event ->
                event.type() == AgentEventType.RUN_STOPPED
                        || event.type() == AgentEventType.RUN_WAITING_APPROVAL));
        new DecisionTraceBuilder().build(publisher.events());

        System.out.printf(
                "P5V_REAL_TOOL modelCalls=%d toolExecutions=%d toolCallId=%s stopReason=%s "
                        + "final=%s events=%s%n",
                runtime.model().requests().size(),
                tool.executions(),
                providerCall.id(),
                result.stopReason(),
                oneLine(result.finalContent()),
                eventTypes(publisher)
        );
    }

    @Test
    void shouldBlockSecondRealModelCallAtModelBudget() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        SmokeRuntime runtime = runtime(List.of(tool), publisher);

        AgentRunResult result = runtime.coordinator().execute(request(
                "p92-ollama-model-budget",
                toolProtocolMessages(),
                Set.of("knowledge_search"),
                ExecutionBudget.builder().maxModelCalls(1).build()
        ));

        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, result.stopReason());
        assertEquals(1, runtime.model().requests().size());
        assertEquals(1, tool.executions());
        assertEquals(1, publisher.events().stream()
                .filter(event -> event.type() == AgentEventType.MODEL_STARTED).count());
        assertEquals(BudgetDimension.MODEL_CALLS, budgetBlock(publisher).dimension());
        new DecisionTraceBuilder().build(publisher.events());
    }

    @Test
    void shouldBlockRealToolBeforeStartAtToolBudget() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        SmokeRuntime runtime = runtime(List.of(tool), publisher);

        AgentRunResult result = runtime.coordinator().execute(request(
                "p92-ollama-tool-budget",
                toolProtocolMessages(),
                Set.of("knowledge_search"),
                ExecutionBudget.builder().maxToolCalls(0).build()
        ));

        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, result.stopReason());
        assertEquals(1, runtime.model().requests().size());
        assertEquals(0, tool.executions());
        assertFalse(eventTypes(publisher).contains(AgentEventType.TOOL_STARTED));
        assertEquals(BudgetDimension.TOOL_CALLS, budgetBlock(publisher).dimension());
        new DecisionTraceBuilder().build(publisher.events());
    }

    @Test
    void shouldBlockFutureRealToolAtConfirmedTokenBudget() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        SmokeRuntime runtime = runtime(List.of(tool), publisher);

        AgentRunResult result = runtime.coordinator().execute(request(
                "p92-ollama-token-budget",
                toolProtocolMessages(),
                Set.of("knowledge_search"),
                ExecutionBudget.builder().maxTotalTokens(1).build()
        ));

        assertTrue(runtime.model().turns().get(0).tokenUsage().isComplete());
        assertEquals(AgentStopReason.BUDGET_EXHAUSTED, result.stopReason());
        assertEquals(0, tool.executions());
        assertEquals(BudgetDimension.TOTAL_TOKENS, budgetBlock(publisher).dimension());
        new DecisionTraceBuilder().build(publisher.events());
    }

    private SmokeRuntime runtime(
            List<? extends AgentTool<?, ?>> tools,
            RecordingAgentEventPublisher publisher
    ) {
        String baseUrl = normalizedBaseUrl(environmentOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL));
        String modelName = environmentOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        assertOllamaReady(baseUrl, modelName);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("ollama-local")
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .maxTokens(256)
                        .build())
                .build();
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingAgentModel model = new RecordingAgentModel(
                new SpringAiOllamaAgentModelAdapter(chatModel, objectMapper)
        );
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                model,
                executor,
                new SpringAiModelToolDefinitionProjector(objectMapper),
                publisher
        );
        return new SmokeRuntime(
                new AgentExecutionCoordinator(runner, RuntimeMiddlewareChain.empty()),
                model
        );
    }

    private void assertOllamaReady(String baseUrl, String modelName) {
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
            boolean found = false;
            for (JsonNode model : models) {
                String registeredName = model.path("name").asText();
                if (registeredName.equals(modelName)
                        || registeredName.equals(modelName + ":latest")) {
                    found = true;
                    break;
                }
            }
            assumeTrue(found, "Ollama model is not registered: " + modelName);
        } catch (Exception exception) {
            assumeTrue(false, "Ollama preflight failed at " + baseUrl + ": "
                    + exception.getMessage());
        }
    }

    private AgentExecutionRequest request(
            String runId,
            List<AgentMessage> messages,
            Set<String> allowedTools
    ) {
        return request(runId, messages, allowedTools, ExecutionBudget.unlimited());
    }

    private AgentExecutionRequest request(
            String runId,
            List<AgentMessage> messages,
            Set<String> allowedTools,
            ExecutionBudget budget
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        "p5v-real-session",
                        messages,
                        3,
                        allowedTools,
                        Set.of(),
                        budget
                ),
                "request-" + runId,
                1L
        );
    }

    private List<AgentMessage> toolProtocolMessages() {
        return List.of(
                AgentMessage.system(
                        "If there is no knowledge_search result, call knowledge_search exactly "
                                + "once with query Redis Sentinel and do not answer. If a result "
                                + "exists, answer from it without another tool call."
                ),
                AgentMessage.user("Use knowledge_search to explain Redis Sentinel.")
        );
    }

    private BudgetBlockedEvent budgetBlock(RecordingAgentEventPublisher publisher) {
        return (BudgetBlockedEvent) publisher.events().stream()
                .filter(event -> event.type() == AgentEventType.BUDGET_BLOCKED)
                .findFirst()
                .orElseThrow();
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizedBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String oneLine(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    private static List<AgentEventType> eventTypes(RecordingAgentEventPublisher publisher) {
        return publisher.events().stream().map(event -> event.type()).toList();
    }

    private record SmokeRuntime(
            AgentExecutionCoordinator coordinator,
            RecordingAgentModel model
    ) {
    }

    private record KnowledgeSearchInput(@NotBlank String query) {
    }

    private static final class KnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        private int executions;
        private String lastQuery;

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return new ToolDescriptor<>(
                    "knowledge_search",
                    "Return a deterministic factual note about Redis Sentinel",
                    KnowledgeSearchInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            executions++;
            lastQuery = input.query();
            return "Redis Sentinel is a high availability mechanism. "
                    + "It monitors Redis nodes and can perform automatic failover.";
        }

        private int executions() {
            return executions;
        }

        private String lastQuery() {
            return lastQuery;
        }
    }

    private static final class RecordingAgentModel implements AgentModel {

        private final AgentModel delegate;
        private final List<AgentModelRequest> requests = new ArrayList<>();
        private final List<ModelTurn> turns = new ArrayList<>();

        private RecordingAgentModel(AgentModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelTurn generate(AgentModelRequest request) {
            requests.add(request);
            ModelTurn turn = delegate.generate(request);
            turns.add(turn);
            return turn;
        }

        private List<AgentModelRequest> requests() {
            return List.copyOf(requests);
        }

        private List<ModelTurn> turns() {
            return List.copyOf(turns);
        }
    }
}
