package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.IntentType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import java.time.Instant;

/**
 * 后台心理报告列表响应。
 */
public record ReportResponse(
        Long id,
        Long userId,
        String username,
        String sessionId,
        IntentType intent,
        EmotionLabel emotion,
        double emotionScore,
        RiskLevel riskLevel,
        double confidence,
        String summary,
        String emotionTags,
        ToolStatus excelStatus,
        ToolStatus emailStatus,
        Instant createdAt
) {
    public static ReportResponse from(PsychologicalReport report) {
        return new ReportResponse(
                report.getId(),
                report.getUser().getId(),
                report.getUser().getUsername(),
                report.getSession() == null ? null : report.getSession().getPublicId(),
                report.getIntent(),
                report.getEmotion(),
                report.getEmotionScore(),
                report.getRiskLevel(),
                report.getConfidence(),
                report.getSummary(),
                report.getEmotionTags(),
                report.getExcelStatus(),
                report.getEmailStatus(),
                report.getCreatedAt());
    }
}
