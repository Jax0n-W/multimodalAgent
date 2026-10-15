package com.multimodalAgent.agent.service.knowledge;

import java.util.List;
import java.util.Objects;

/** Result and truthful execution metadata from the optional reranking stage. */
public record RerankOutcome(List<SearchResult> results, boolean applied, String detail) {

    public RerankOutcome {
        results = List.copyOf(Objects.requireNonNull(results, "results must not be null"));
        detail = detail == null ? "" : detail;
    }
}
