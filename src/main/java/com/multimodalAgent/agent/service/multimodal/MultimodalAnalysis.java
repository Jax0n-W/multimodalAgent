package com.multimodalAgent.agent.service.multimodal;

import com.multimodalAgent.agent.service.PsychologyAssessment;
import java.util.List;

/**
 * 多模态接入层完成后的统一输入格式。
 */
public record MultimodalAnalysis(
        String userText,
        String modelText,
        PsychologyAssessment fusedAssessment,
        List<MultimodalSignal> signals,
        String summary,
        String emotionTagsJson
) {
}
