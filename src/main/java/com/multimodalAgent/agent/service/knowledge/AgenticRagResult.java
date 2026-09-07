package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/**
 * Agentic RAG 的检索过程结果。
 */
public record AgenticRagResult(
        String plan,
        List<String> queries,
        List<SearchResult> evidence,
        String review,
        boolean sufficient
) {
    public static AgenticRagResult empty() {
        return new AgenticRagResult("未触发 RAG", List.of(), List.of(), "无", false);
    }

    public String contextBlock() {
        if (evidence.isEmpty()) {
            return """
                    Agentic RAG 计划：%s
                    Agentic RAG 复核：未检索到足够知识。回答时必须说明知识库不足，并给出安全、通用建议。
                    """.formatted(plan);
        }
        String evidenceText = String.join("\n\n", evidence.stream()
                .map(result -> "- [" + result.source() + " | score %.3f] %s"
                        .formatted(result.score(), result.content()))
                .toList());
        return """
                Agentic RAG 计划：%s
                Agentic RAG 查询：%s
                Agentic RAG 复核：%s
                检索知识：
                %s
                """.formatted(plan, String.join("；", queries), review, evidenceText);
    }
}
