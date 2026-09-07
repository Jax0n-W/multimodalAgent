package com.multimodalAgent.agent.service.multimodal;

import com.multimodalAgent.agent.domain.EmotionLabel;

/**
 * 单个模态输出的情绪信号。
 */
public record MultimodalSignal(
        String modality,
        EmotionLabel emotion,
        double score,
        double confidence,
        String evidence
) {
}
