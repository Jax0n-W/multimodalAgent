package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.IntentType;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.ToolStatus;
import java.time.Instant;

/**
 * 管理员后台 Excel 写入数据响应。
 */
public record ExcelRecordResponse(
        Long reportId,
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
        String content,
        ToolStatus excelStatus,
        Instant createdAt
) {
    public static ExcelRecordResponse from(PsychologicalReport report) {
        return new ExcelRecordResponse(
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
                report.getContent(),
                report.getExcelStatus(),
                report.getCreatedAt());
    }
}
