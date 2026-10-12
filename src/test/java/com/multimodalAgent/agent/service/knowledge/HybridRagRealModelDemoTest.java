package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Opt-in demonstration that feeds real Hybrid RAG evidence to the local project model. */
@Tag("real-model")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p12-real-model;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.knowledge.hybrid-mvp-enabled=true",
        "multimodal-agent.knowledge.retrieval-mode=HYBRID_RRF",
        "multimodal-agent.knowledge.embedding-dimensions=3",
        "multimodal-agent.knowledge.chunk-size=2048",
        "multimodal-agent.knowledge.chunk-overlap=0",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
class HybridRagRealModelDemoTest {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "mindbridge-qwen2.5-7b-ft:latest";
    private static final List<List<String>> TOPICS = List.of(
            List.of("睡", "失眠", "作息", "sleep"),
            List.of("学业", "考试", "复习", "academic"),
            List.of("室友", "朋友", "人际", "沟通", "interpersonal")
    );

    @Autowired private KnowledgeService knowledgeService;
    @Autowired private KnowledgeChunkRepository chunks;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private EmbeddingClient embeddingClient;
    @MockBean private ChromaGateway chromaGateway;
    @MockBean private KnowledgeIngestionService startupIngestion;

    @BeforeEach
    void seedPublicCampusKnowledge() {
        chunks.deleteAll();
        when(embeddingClient.modelName()).thenReturn("p12-demo-embedding-v1");
        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                embedding(invocation.getArgument(0))
        );
        knowledgeService.ingest(
                "sleep.md",
                "校园睡眠建议：保持固定起床时间，睡前减少咖啡因和屏幕刺激。"
        );
        knowledgeService.ingest(
                "academic.md",
                "校园学业支持：把考试复习拆成小步骤，安排短暂休息，必要时联系学习支持中心。"
        );
        knowledgeService.ingest(
                "interpersonal.md",
                "校园人际支持：与室友或朋友冲突时先暂停升级，区分事实与猜测，清楚表达需要和边界。"
        );
    }

    @Test
    void answersSleepAcademicAndInterpersonalCasesFromRetrievedEvidence() throws Exception {
        String baseUrl = normalized(environmentOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL));
        String model = environmentOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        assumeOllamaReady(baseUrl, model);

        List<DemoCase> cases = List.of(
                new DemoCase("睡眠", "失眠时如何调整睡眠作息？", "sleep.md"),
                new DemoCase("学业压力", "考试和复习压力很大时怎么办？", "academic.md"),
                new DemoCase("人际问题", "和室友发生人际冲突时怎么沟通？", "interpersonal.md")
        );
        for (DemoCase demo : cases) {
            KnowledgeRetrievalResult retrieval = knowledgeService.retrieve(
                    demo.question(), 3, KnowledgeRetrievalMode.HYBRID_RRF
            );
            assertEquals(KnowledgeRetrievalStatus.HYBRID, retrieval.status());
            assertFalse(retrieval.results().isEmpty());
            assertEquals(demo.expectedSource(), retrieval.results().get(0).source());

            String answer = complete(baseUrl, model, demo.question(), retrieval.results().get(0));
            assertFalse(answer.isBlank());
            assertTrue(answer.contains("EVIDENCE_USED=" + demo.expectedSource()), answer);
            System.out.printf(
                    Locale.ROOT,
                    "P12_REAL_MODEL_DEMO case=%s status=%s source=%s answer=%s%n",
                    demo.name(), retrieval.status(), demo.expectedSource(), oneLine(answer)
            );
        }
    }

    private String complete(
            String baseUrl,
            String model,
            String question,
            SearchResult evidence
    ) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "temperature", 0,
                "max_tokens", 192,
                "messages", List.of(
                        Map.of(
                                "role", "system",
                                "content", "请仅根据提供的校园知识证据给出简短、支持性的中文回答。"
                                        + "证据不足时应明确说明。答案最后一行必须原样写 "
                                        + "EVIDENCE_USED=" + evidence.source()
                        ),
                        Map.of(
                                "role", "user",
                                "content", "问题：" + question + "\n证据来源："
                                        + evidence.source() + "\n证据内容：" + evidence.content()
                        )
                )
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode root = objectMapper.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    private List<Double> embedding(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Double> vector = new ArrayList<>(TOPICS.size());
        for (List<String> terms : TOPICS) {
            vector.add((double) terms.stream().filter(normalized::contains).count());
        }
        return List.copyOf(vector);
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
            String expected = stripLatest(modelName);
            boolean found = false;
            for (JsonNode registered : objectMapper.readTree(response.body()).path("models")) {
                if (stripLatest(registered.path("name").asText()).equals(expected)) {
                    found = true;
                    break;
                }
            }
            assumeTrue(found, "Ollama model is not registered: " + modelName);
        } catch (Exception exception) {
            assumeTrue(false, "Ollama preflight failed: " + exception.getMessage());
        }
    }

    private String stripLatest(String model) {
        return model.endsWith(":latest")
                ? model.substring(0, model.length() - ":latest".length())
                : model;
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalized(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private record DemoCase(String name, String question, String expectedSource) {
    }
}
