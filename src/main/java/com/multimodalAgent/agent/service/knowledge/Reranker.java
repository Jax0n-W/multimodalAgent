package com.multimodalAgent.agent.service.knowledge;

import java.util.List;

/** Extensible post-RRF query-document reranking boundary. */
public interface Reranker {

    RerankOutcome rerank(String query, List<SearchResult> candidates, int topK);
}
