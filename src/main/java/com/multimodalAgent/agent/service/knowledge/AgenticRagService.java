package com.multimodalAgent.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
/**
 * Agentic RAG 编排服务。
 *
 * <p>先让模型生成检索计划和多个查询，再检索、去重、复核；知识不足时进行一次补充检索。</p>
 */
public class AgenticRagService {

    private static final int MAX_QUERIES = 3;

    private final KnowledgeService knowledgeService;
    private final multimodalAgentProperties properties;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public AgenticRagService(
            KnowledgeService knowledgeService,
            multimodalAgentProperties properties,
            AiClient aiClient,
            ObjectMapper objectMapper
    ) {
        this.knowledgeService = knowledgeService;
        this.properties = properties;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    public AgenticRagResult retrieve(String userInput, List<AiMessage> history) {
        RagPlan plan = plan(userInput, history);
        SearchBatch batch = search(plan.queries(), properties.getKnowledge().getTopK());
        List<SearchResult> evidence = batch.evidence();
        RagReview review = review(userInput, evidence);
        if (!review.sufficient()) {
            List<SearchResult> expanded = new ArrayList<>(evidence);
            SearchBatch followUp = search(
                    review.followUpQueries(), properties.getKnowledge().getTopK()
            );
            expanded.addAll(followUp.evidence());
            evidence = dedupe(expanded, properties.getKnowledge().getTopK());
            batch = new SearchBatch(evidence, combineStatus(batch.status(), followUp.status()));
            review = review(userInput, evidence);
        }
        return new AgenticRagResult(
                plan.reason(), plan.queries(), evidence, review.reason(), review.sufficient(),
                batch.status()
        );
    }

    private RagPlan plan(String userInput, List<AiMessage> history) {
        try {
            String raw = aiClient.complete(PromptTemplates.agenticRagPlanPrompt(history, userInput));
            JsonNode node = objectMapper.readTree(extractJson(raw));
            List<String> queries = jsonStrings(node.path("queries"));
            if (queries.isEmpty()) {
                queries = List.of(userInput);
            }
            return new RagPlan(
                    node.path("reason").asText("围绕用户当前心理支持需求检索校园心理健康知识。"),
                    queries.stream().limit(MAX_QUERIES).toList());
        } catch (Exception ignored) {
            return new RagPlan("模型规划失败，使用用户原问题直接检索。", List.of(userInput));
        }
    }

    private RagReview review(String userInput, List<SearchResult> evidence) {
        try {
            String raw = aiClient.complete(PromptTemplates.agenticRagReviewPrompt(userInput, evidence));
            JsonNode node = objectMapper.readTree(extractJson(raw));
            return new RagReview(
                    node.path("sufficient").asBoolean(false),
                    node.path("reason").asText("证据覆盖度不足。"),
                    jsonStrings(node.path("followUpQueries")));
        } catch (Exception ignored) {
            return new RagReview(!evidence.isEmpty(), evidence.isEmpty() ? "未找到可用证据。" : "已找到可用知识片段。", List.of(userInput));
        }
    }

    private SearchBatch search(List<String> queries, int topK) {
        List<SearchResult> merged = new ArrayList<>();
        KnowledgeRetrievalStatus status = KnowledgeRetrievalStatus.NO_EVIDENCE;
        for (String query : queries) {
            if (query != null && !query.isBlank()) {
                KnowledgeRetrievalResult result = knowledgeService.retrieveWithStatus(query, topK);
                merged.addAll(result.results());
                status = combineStatus(status, result.status());
            }
        }
        return new SearchBatch(dedupe(merged, topK), status);
    }

    private KnowledgeRetrievalStatus combineStatus(
            KnowledgeRetrievalStatus left,
            KnowledgeRetrievalStatus right
    ) {
        if (left == KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED
                || right == KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED) {
            return KnowledgeRetrievalStatus.LEXICAL_ONLY_DEGRADED;
        }
        if (left == KnowledgeRetrievalStatus.DENSE_UNAVAILABLE
                || right == KnowledgeRetrievalStatus.DENSE_UNAVAILABLE) {
            return KnowledgeRetrievalStatus.DENSE_UNAVAILABLE;
        }
        if (left == KnowledgeRetrievalStatus.HYBRID
                || right == KnowledgeRetrievalStatus.HYBRID) {
            return KnowledgeRetrievalStatus.HYBRID;
        }
        if (left == KnowledgeRetrievalStatus.DENSE_ONLY
                || right == KnowledgeRetrievalStatus.DENSE_ONLY) {
            return KnowledgeRetrievalStatus.DENSE_ONLY;
        }
        if (left == KnowledgeRetrievalStatus.BM25_ONLY
                || right == KnowledgeRetrievalStatus.BM25_ONLY) {
            return KnowledgeRetrievalStatus.BM25_ONLY;
        }
        if (left == KnowledgeRetrievalStatus.LEGACY_FALLBACK
                || right == KnowledgeRetrievalStatus.LEGACY_FALLBACK) {
            return KnowledgeRetrievalStatus.LEGACY_FALLBACK;
        }
        return KnowledgeRetrievalStatus.NO_EVIDENCE;
    }

    private List<SearchResult> dedupe(List<SearchResult> results, int topK) {
        Map<String, SearchResult> best = new LinkedHashMap<>();
        for (SearchResult result : results) {
            String key = result.chunkId() == null ? result.source() + ":" + result.content() : "id:" + result.chunkId();
            SearchResult previous = best.get(key);
            if (previous == null || result.score() > previous.score()) {
                best.put(key, result);
            }
        }
        return best.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    private List<String> jsonStrings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        node.forEach(item -> {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private record RagPlan(String reason, List<String> queries) {
    }

    private record RagReview(boolean sufficient, String reason, List<String> followUpQueries) {
    }

    private record SearchBatch(
            List<SearchResult> evidence,
            KnowledgeRetrievalStatus status
    ) {
        private SearchBatch {
            evidence = List.copyOf(evidence);
        }
    }
}
