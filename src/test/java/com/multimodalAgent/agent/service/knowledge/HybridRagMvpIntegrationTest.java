package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.repository.KnowledgeChunkRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p12-hybrid;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.knowledge.hybrid-mvp-enabled=true",
        "multimodal-agent.knowledge.max-index-chunks=100",
        "multimodal-agent.knowledge.embedding-dimensions=10",
        "multimodal-agent.knowledge.chunk-size=2048",
        "multimodal-agent.knowledge.chunk-overlap=0",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
class HybridRagMvpIntegrationTest {

    private static final List<List<String>> TOPIC_TERMS = List.of(
            List.of("睡", "失眠", "作息", "入睡", "sleep", "bedtime"),
            List.of("学业", "考试", "论文", "复习", "academic", "study", "workload"),
            List.of("室友", "朋友", "人际", "冲突", "边界", "roommate", "relationship"),
            List.of("心理中心", "咨询", "预约", "counseling", "appointment"),
            List.of("资助", "奖学金", "助学金", "补助", "勤工", "financial", "grant"),
            List.of("图书馆", "研讨室", "数据库", "引用", "library", "study room"),
            List.of("校医院", "门诊", "急症", "呼吸困难", "clinic", "medical", "emergency"),
            List.of("运动", "散步", "拉伸", "强度", "exercise", "walking", "stretching"),
            List.of("正念", "呼吸", "当下", "mindfulness", "breathing"),
            List.of("就业", "简历", "面试", "实习", "职业", "career", "resume", "interview")
    );

    @Autowired private KnowledgeService knowledgeService;
    @Autowired private KnowledgeChunkRepository chunks;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private multimodalAgentProperties properties;

    @MockBean private EmbeddingClient embeddingClient;
    @MockBean private ChromaGateway chromaGateway;
    @MockBean private KnowledgeIngestionService startupIngestion;

    private EvaluationSuite suite;

    @BeforeEach
    void setUp() throws Exception {
        chunks.deleteAll();
        properties.getKnowledge().setHybridMvpEnabled(true);
        properties.getKnowledge().setRetrievalMode(KnowledgeRetrievalMode.HYBRID_RRF);
        properties.getKnowledge().setMaxIndexChunks(100);
        when(embeddingClient.modelName()).thenReturn("deterministic-campus-v1");
        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                deterministicEmbedding(invocation.getArgument(0))
        );
        try (InputStream stream = getClass().getResourceAsStream(
                "/eval/p12-hybrid-rag-mvp-suite.json"
        )) {
            suite = objectMapper.readValue(stream, EvaluationSuite.class);
        }
        for (Document document : suite.documents()) {
            assertEquals(1, knowledgeService.ingest(document.source(), document.content()));
        }
    }

    @Test
    void bm25DenseAndHybridModesAreRealAndDeterministic() {
        for (KnowledgeRetrievalMode mode : KnowledgeRetrievalMode.values()) {
            KnowledgeRetrievalResult first = knowledgeService.retrieve(
                    "失眠如何调整睡眠作息", 5, mode
            );
            KnowledgeRetrievalResult second = knowledgeService.retrieve(
                    "失眠如何调整睡眠作息", 5, mode
            );

            assertFalse(first.results().isEmpty());
            assertEquals("sleep.md", first.results().get(0).source());
            assertEquals(
                    first.results().stream().map(SearchResult::chunkId).toList(),
                    second.results().stream().map(SearchResult::chunkId).toList()
            );
            assertEquals(expectedStatus(mode), first.status());
            assertEquals(
                    first.results().size(),
                    first.results().stream().map(SearchResult::chunkId).distinct().count()
            );
            if (mode == KnowledgeRetrievalMode.HYBRID_RRF) {
                assertEquals(2.0 / 61.0, first.results().get(0).score(), 1.0e-12);
            }
        }
    }

    @Test
    void embeddingUnavailabilityAndModelMismatchAreTruthfullyDegraded() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of());

        KnowledgeRetrievalResult unavailable = knowledgeService.retrieve(
                "失眠如何调整作息", 5, KnowledgeRetrievalMode.HYBRID_RRF
        );

        assertEquals(KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED, unavailable.status());
        assertEquals("sleep.md", unavailable.results().get(0).source());

        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                deterministicEmbedding(invocation.getArgument(0))
        );
        when(embeddingClient.modelName()).thenReturn("different-model-v2");

        KnowledgeRetrievalResult mismatch = knowledgeService.retrieve(
                "失眠如何调整作息", 5, KnowledgeRetrievalMode.HYBRID_RRF
        );
        assertEquals(KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED, mismatch.status());
        assertTrue(mismatch.detail().contains("model"));
    }

    @Test
    void noEvidenceReturnsNoEvidenceWithoutFabricatingSources() {
        KnowledgeRetrievalResult result = knowledgeService.retrieve(
                "ZXQJ-999-unrelated", 5, KnowledgeRetrievalMode.HYBRID_RRF
        );

        assertEquals(KnowledgeRetrievalStatus.NO_EVIDENCE, result.status());
        assertTrue(result.results().isEmpty());
    }

    @Test
    void rejectsEmbeddingDimensionAndSharedIndexOverflow() {
        when(embeddingClient.embed(anyString())).thenReturn(List.of(1.0, 0.0));
        assertThrows(
                IllegalStateException.class,
                () -> knowledgeService.ingest("wrong-dimension.md", "public content")
        );

        when(embeddingClient.embed(anyString())).thenAnswer(invocation ->
                deterministicEmbedding(invocation.getArgument(0))
        );
        properties.getKnowledge().setMaxIndexChunks(10);
        assertThrows(
                IllegalStateException.class,
                () -> knowledgeService.ingest("overflow.md", "sleep content")
        );
    }

    @Test
    void featureSwitchKeepsTheExistingRetrieverAsRollbackPath() {
        properties.getKnowledge().setHybridMvpEnabled(false);

        KnowledgeRetrievalResult result = knowledgeService.retrieveWithStatus(
                "失眠如何调整作息", 5
        );

        assertEquals(KnowledgeRetrievalStatus.LEGACY_FALLBACK, result.status());
        assertFalse(result.results().isEmpty());
    }

    @Test
    void evaluatesThirtyPublicCampusQueriesWithMeasuredMetrics() {
        assertTrue(suite.cases().size() >= 30);
        Map<KnowledgeRetrievalMode, Metrics> metrics = new LinkedHashMap<>();
        for (KnowledgeRetrievalMode mode : KnowledgeRetrievalMode.values()) {
            Metrics value = evaluate(mode);
            metrics.put(mode, value);
            System.out.printf(
                    Locale.ROOT,
                    "P12_RETRIEVAL_EVAL mode=%s cases=%d recallAt5=%.4f mrrAt10=%.4f "
                            + "top1SourceAccuracy=%.4f averageLatencyMs=%.3f%n",
                    mode, value.cases(), value.recallAt5(), value.mrrAt10(),
                    value.top1SourceAccuracy(), value.averageLatencyNanos() / 1_000_000.0
            );
        }
        Metrics bm25 = metrics.get(KnowledgeRetrievalMode.BM25_ONLY);
        Metrics dense = metrics.get(KnowledgeRetrievalMode.DENSE_ONLY);
        Metrics hybrid = metrics.get(KnowledgeRetrievalMode.HYBRID_RRF);

        // The corpus intentionally includes ten English paraphrases for Chinese-only
        // documents. BM25 cannot bridge that vocabulary gap; dense retrieval must.
        assertTrue(bm25.recallAt5() >= 0.65);
        assertTrue(bm25.mrrAt10() >= 0.65);
        assertTrue(dense.recallAt5() >= 0.95);
        assertTrue(dense.mrrAt10() >= 0.90);
        assertTrue(hybrid.recallAt5() >= 0.95);
        assertTrue(hybrid.mrrAt10() >= 0.85);
        assertTrue(metrics.get(KnowledgeRetrievalMode.HYBRID_RRF).recallAt5()
                >= metrics.get(KnowledgeRetrievalMode.BM25_ONLY).recallAt5());
        assertTrue(metrics.get(KnowledgeRetrievalMode.HYBRID_RRF).recallAt5()
                >= metrics.get(KnowledgeRetrievalMode.DENSE_ONLY).recallAt5());
    }

    private Metrics evaluate(KnowledgeRetrievalMode mode) {
        int recallHits = 0;
        int top1Hits = 0;
        double reciprocalRanks = 0.0;
        long latency = 0;
        for (EvaluationCase testCase : suite.cases()) {
            KnowledgeRetrievalResult result = knowledgeService.retrieve(
                    testCase.query(), 10, mode
            );
            latency += result.latencyNanos();
            List<String> sources = result.results().stream().map(SearchResult::source).toList();
            int rank = sources.indexOf(testCase.expectedSource());
            if (rank >= 0 && rank < 5) {
                recallHits++;
            }
            if (rank >= 0 && rank < 10) {
                reciprocalRanks += 1.0 / (rank + 1);
            }
            if (rank == 0) {
                top1Hits++;
            }
        }
        int total = suite.cases().size();
        return new Metrics(
                total,
                recallHits / (double) total,
                reciprocalRanks / total,
                top1Hits / (double) total,
                latency / total
        );
    }

    private List<Double> deterministicEmbedding(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        List<Double> vector = new ArrayList<>(TOPIC_TERMS.size());
        for (List<String> terms : TOPIC_TERMS) {
            double matched = terms.stream().filter(normalized::contains).count();
            vector.add(matched);
        }
        return List.copyOf(vector);
    }

    private KnowledgeRetrievalStatus expectedStatus(KnowledgeRetrievalMode mode) {
        return switch (mode) {
            case BM25_ONLY -> KnowledgeRetrievalStatus.BM25_ONLY;
            case DENSE_ONLY -> KnowledgeRetrievalStatus.DENSE_ONLY;
            case HYBRID_RRF -> KnowledgeRetrievalStatus.HYBRID;
        };
    }

    private record EvaluationSuite(
            String suiteId,
            String version,
            String provenance,
            List<Document> documents,
            List<EvaluationCase> cases
    ) {
    }

    private record Document(String source, String content) {
    }

    private record EvaluationCase(String id, String query, String expectedSource) {
    }

    private record Metrics(
            int cases,
            double recallAt5,
            double mrrAt10,
            double top1SourceAccuracy,
            long averageLatencyNanos
    ) {
    }
}
