package com.multimodalAgent.agent.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 工具调用使用的报告载荷。
 */
public final class ReportPayloads {

    private ReportPayloads() {
    }

    public static Map<String, Object> from(PsychologicalReport report) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", report.getId());
        payload.put("userId", report.getUser().getId());
        payload.put("username", report.getUser().getUsername());
        payload.put("sessionId", report.getSession() == null ? "" : report.getSession().getPublicId());
        payload.put("intent", report.getIntent().name());
        payload.put("emotion", report.getEmotion().name());
        payload.put("emotionScore", report.getEmotionScore());
        payload.put("riskLevel", report.getRiskLevel().name());
        payload.put("confidence", report.getConfidence());
        payload.put("summary", report.getSummary());
        payload.put("emotionTags", report.getEmotionTags());
        payload.put("content", report.getContent());
        payload.put("createdAt", report.getCreatedAt().toString());
        return payload;
    }

    public static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    public static long number(JsonNode node, String field) {
        return node == null ? 0L : node.path(field).asLong(0L);
    }

    public static double decimal(JsonNode node, String field) {
        return node == null ? 0.0 : node.path(field).asDouble(0.0);
    }
}
