package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.ModelCallMetadata;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.stream.ModelDelta;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in P8.2 smoke against the already-running local Ollama OpenAI-compatible endpoint. */
@Tag("real-model")
class RealStreamingModelSmokeTest {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "mindbridge-qwen2.5-7b-ft:latest";

    @Test
    void streamsTextAndReconstructsOneCompleteTurn() {
        String baseUrl = normalized(environmentOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL));
        String model = environmentOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        assumeOllamaReady(baseUrl, model);
        ObjectMapper objectMapper = new ObjectMapper();
        List<ModelDelta> deltas = new ArrayList<>();
        AtomicInteger providerCalls = new AtomicInteger();
        SpringWebClientOpenAiCompatibleStreamingClient providerClient =
                new SpringWebClientOpenAiCompatibleStreamingClient(
                        baseUrl,
                        "ollama-local",
                        objectMapper
                );
        OpenAiCompatibleStreamingClient countingClient = request -> {
            providerCalls.incrementAndGet();
            return providerClient.stream(request);
        };
        StreamingModelInvocationScope scope = new StreamingModelInvocationScope();
        OpenAiCompatibleStreamingAgentModelAdapter adapter =
                new OpenAiCompatibleStreamingAgentModelAdapter(
                        countingClient,
                        new OpenAiCompatibleStreamingOptions("Ollama", model, 0.0, 128),
                        objectMapper,
                        scope
                );
        AgentModelRequest request = new AgentModelRequest(
                List.of(
                        AgentMessage.system(
                                "Answer directly without tools using one short sentence."
                        ),
                        AgentMessage.user("What is two plus two?")
                ),
                List.of()
        );
        AgentRuntimeContext context = AgentRuntimeContext.minimal(
                "real-streaming-smoke",
                "real-streaming-smoke-session"
        );
        StreamingModelInvocationScope.registerObserver(context, deltas::add);

        ModelTurn turn = scope.aroundModelCall(
                context,
                new ModelCallMetadata(1, request.messages().size()),
                () -> adapter.generate(request)
        );

        assertEquals(ModelFinishReason.STOP, turn.finishReason());
        assertEquals(1, providerCalls.get());
        assertFalse(turn.content().isBlank());
        assertFalse(deltas.isEmpty());
        assertEquals(
                turn.content(),
                deltas.stream().map(ModelDelta::content).reduce("", String::concat)
        );
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

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalized(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
