package com.multimodalAgent.agent.tool.builtin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record KnowledgeSearchInput(
        @NotBlank String query,
        @Min(1) @Max(20) Integer topK
) {

    public static final int DEFAULT_TOP_K = 5;

    public int effectiveTopK() {
        return topK == null ? DEFAULT_TOP_K : topK;
    }
}
