package com.multimodalAgent.agent.service.knowledge;

/** Retrieval modes exposed by the bounded P12 Hybrid RAG MVP. */
public enum KnowledgeRetrievalMode {
    BM25_ONLY,
    DENSE_ONLY,
    HYBRID_RRF
}
