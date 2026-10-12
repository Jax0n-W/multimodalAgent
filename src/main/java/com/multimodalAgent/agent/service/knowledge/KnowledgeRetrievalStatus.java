package com.multimodalAgent.agent.service.knowledge;

/** Truthful execution status for one knowledge retrieval. */
public enum KnowledgeRetrievalStatus {
    BM25_ONLY,
    DENSE_ONLY,
    HYBRID,
    LEXICAL_ONLY_DEGRADED,
    DENSE_UNAVAILABLE,
    LEGACY_FALLBACK,
    NO_EVIDENCE
}
