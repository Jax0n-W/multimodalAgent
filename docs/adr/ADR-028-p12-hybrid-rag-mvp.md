# ADR-028: Bounded Hybrid RAG MVP Delivery

- Status: Accepted for P12 MVP
- Date: 2026-10-12
- Phase: P12 final MVP
- Refines: ADR-027

## Context

ADR-027 describes the production target. The final delivery sprint explicitly requires a small,
working Hybrid RAG MVP and prohibits expanding the system into the complete generation, ACL and
publication control plane. This record states exactly what is delivered now so the implementation
is not mistaken for full ADR-027 conformance.

The shared corpus is limited to administrator-maintained or bundled public-campus documents. The
student chat and durable conversation-memory paths do not ingest content into this corpus. The
existing `/api/admin/knowledge` boundary remains role-protected by server security configuration.

## Decision

### Retrieval modes

`KnowledgeService` exposes independently testable `BM25_ONLY`, `DENSE_ONLY` and `HYBRID_RRF`
modes over the existing `knowledge_chunks` store.

- BM25 uses `k1=1.2`, `b=0.75` and the standard positive IDF form
  `ln(1 + (N - df + 0.5) / (df + 0.5))`.
- Chinese text is normalized with Unicode NFKC and tokenized into Han unigrams and adjacent
  bigrams. ASCII letters and digits are lower-cased and grouped into terms.
- Dense retrieval uses cosine similarity over embeddings persisted with schema version, exact model
  identity and dimension. Query/document dimension and configured maximums are enforced.
- Hybrid fusion uses exact rational `sum(1/(60 + rank))`. It deduplicates by stable chunk identity
  and uses the ADR-027 stable tie-break sequence.

The MVP bounds the in-process index with `max-index-chunks` (default 2,000) and embedding dimensions
with `max-embedding-dimensions` (default 3,072). An unavailable or incompatible embedding branch is
reported as `LEXICAL_ONLY_DEGRADED`; it is never labeled Hybrid. Dense-only reports
`DENSE_UNAVAILABLE`. Empty results report `NO_EVIDENCE`.

`RAG_HYBRID_MVP_ENABLED=false` routes the existing call surface through the pre-P12 retriever as a
rollback path. Existing documents whose embedding payload predates the versioned envelope require
administrator re-ingestion for the dense branch; until then Hybrid truthfully degrades to lexical.

### Runtime and safety

- CHAT performs no retrieval.
- CONSULT and HIGH-risk routes reuse `AgenticRagService` and the configured Hybrid retriever.
- HIGH-risk report and notification facts are committed before optional retrieval.
- Retrieved text enters Runtime only through `UntrustedEvidenceContextSource` and is frozen in the
  existing context snapshot. Recovery reuses that snapshot and does not retrieve historical
  evidence again.
- A trusted server-side risk decision disables ordinary Skill context for HIGH-risk requests,
  including a benign text prompt whose multimodal fusion independently establishes HIGH risk.
  This decision does not parse prompt text and does not alter AgentRunner, RecoveryEngine, tool
  governance or the SSE contract.

### Evaluation evidence

The deterministic suite contains 10 synthetic public-campus documents and 30 queries, including 10
English paraphrases of Chinese-only documents. It contains no student data. On the local H2/JVM
test run used for this delivery:

| Mode | Recall@5 | MRR@10 | Top-1 source accuracy |
| --- | ---: | ---: | ---: |
| BM25-only | 0.6667 | 0.6667 | 0.6667 |
| Dense-only deterministic fixture | 1.0000 | 1.0000 | 1.0000 |
| Hybrid RRF deterministic fixture | 1.0000 | 1.0000 | 1.0000 |

Latency is measured and printed per run because it is machine-dependent; it is not promoted to a
portable production claim. The suite also verifies no-evidence behavior, source correctness,
deterministic ranking, dense degradation, model mismatch, dimension rejection, corpus bounds and
legacy rollback.

An opt-in real-model test retrieves Hybrid evidence and supplies it to the locally registered
`mindbridge-qwen2.5-7b-ft:latest` model for sleep, academic-pressure and interpersonal cases. It
requires the answer to identify the actual retrieved source. Provider-dependent results remain
separate from the default offline gate.

## Deferred production capabilities

This MVP does **not** claim the following ADR-027 production capabilities:

- immutable document/version/chunk/citation identities and a Flyway-owned knowledge control plane;
- per-principal ACL filtering, revocation joins, tenant/corpus isolation or private-document search;
- MySQL ngram FULLTEXT and its MySQL-specific ranking/readiness verification;
- Chroma v2, pinned image digest, explicit-vector publication and post-authorization;
- immutable index generations, exact-set manifests, CAS publication, rollback and garbage
  collection;
- durable ingestion/reindex jobs, cross-store atomic publication or zero-downtime embedding-space
  migration;
- typed generation-bound evidence/citations and governed historical tool retry against a retained
  generation;
- production relevance corpus, nDCG/no-answer/ACL security baselines, load tests and latency SLOs;
  or
- removal of the legacy `AgenticRagService` planning/review calls.

These remain future production hardening work. They are deliberately excluded from P12/P13 final
delivery rather than partially implemented or represented as complete.

## Consequences

The repository now has a real, bounded two-branch Hybrid retrieval path suitable for a small campus
demo, an honest degradation contract and a tested rollback switch. It is not a production
multi-tenant knowledge platform. P13 may perform delivery verification and documentation only; it
must not use this ADR as authority to introduce the deferred architecture.
