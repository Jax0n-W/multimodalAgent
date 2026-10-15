package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Explicit truthful fallback used when model reranking is disabled. */
public class PassThroughReranker implements Reranker {

    @Override
    public RerankOutcome rerank(String query, List<SearchResult> candidates, int topK) {
        List<SearchResult> limited = candidates.stream().limit(topK).toList();
        return new RerankOutcome(limited, false, "reranker disabled; preserved RRF order");
    }
}
