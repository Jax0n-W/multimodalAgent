package com.multimodalAgent.agent.service.knowledge;

import java.util.List;
import java.util.Objects;

/** Evidence and mode/provenance facts produced by one retrieval attempt. */
public record KnowledgeRetrievalResult(
        KnowledgeRetrievalMode requestedMode,
        KnowledgeRetrievalStatus status,
        List<SearchResult> results,
        long latencyNanos,
        String detail
) {
    public KnowledgeRetrievalResult {
        Objects.requireNonNull(requestedMode, "requestedMode must not be null");
        Objects.requireNonNull(status, "status must not be null");
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must not be negative");
        }
        detail = detail == null ? "" : detail;
    }
}
