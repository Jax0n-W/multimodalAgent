# ADR-027: Knowledge Base and Hybrid Retrieval Contract

- Status: Proposed for P12.1 independent architecture review
- Date: 2026-10-11
- Phase: P12.1
- Builds on: ADR-015 and ADR-022 through ADR-026

## Context

The repository has a useful prototype, not a production Hybrid RAG implementation. `KnowledgeService`
stores mutable `KnowledgeChunk` rows, mirrors writes to `ChromaGateway`, and chooses Chroma, a full
database scan with application-side cosine similarity, or `TokenVectorizer` score blending. Those
are fallback alternatives; they are not independent lexical and dense branches followed by fusion.
The table is Hibernate-managed rather than Flyway-owned, `SearchResult` lacks version and citation
provenance, and neither the SQL nor Chroma path enforces a document ACL.

`ChromaGateway` uses legacy `/api/v1` endpoints and can ask Chroma to embed raw text. The application
can separately use `OpenAiEmbeddingClient`, so the two stores are not guaranteed to share an
embedding space. Writes to Chroma occur inside the apparent ingestion transaction but are neither
transactional with MySQL nor recoverably published. `AgenticRagService` adds auxiliary model
planning/review calls outside Model Gateway and Runtime budgets. ADR-025 explicitly records this as
temporary legacy behavior.

P12 must add production knowledge infrastructure without changing the accepted P6-P11 execution,
snapshot, recovery, trust, chat-history, SSE, or high-risk contracts.

## Required decision index

| # | Decision | Resolved outcome |
| --- | --- | --- |
| 1 | Lexical/Chinese | MySQL 8 InnoDB ngram FULLTEXT, `ngram_token_size=2`, MySQL-tested. |
| 2 | Vector/version | Chroma 1.5.9 REST v2, immutable image digest, startup version/collection verification. |
| 3 | IDs/versions | UUIDv7 logical document plus deterministic SHA-256 version/chunk/citation identities. |
| 4 | Consistency/publication | Complete immutable generation, exact-set verification, MySQL CAS active pointer. |
| 5 | Embedding migration | Versioned embedding space; incompatible changes require a new generation/reindex. |
| 6 | ACL/scope | Trusted server principal; both-branch filtering plus mandatory MySQL dense post-check. |
| 7 | Fusion/order | RRF k=60; deterministic branch/final tie-breaks; no raw-score blending. |
| 8 | Reranker | Optional/off by default; bounded, budget-owned, timeout falls back to exact RRF. |
| 9 | Evidence/citation | Typed immutable version/chunk/generation provenance and authorization-checked citation. |
| 10 | Two use modes | Frozen pre-admission evidence or governed Runtime tool; no automatic duplicate execution. |
| 11 | Legacy migration | Feature-flag/shadow/canary/rollback; no legacy removal before P12.6. |
| 12 | Delete/revoke | Immediate authoritative deny, asynchronous derived cleanup, historical snapshots unchanged. |
| 13 | HIGH risk | Durable risk/report/outbox before retrieval; trusted generic fallback, no fabricated contacts. |
| 14 | Evaluation | Separate offline versioned retrieval suite with proposed gates and opt-in providers. |

## Decision

### 1. System of record and ownership

MySQL is the only authoritative knowledge control plane. It owns logical documents, immutable
versions, chunks, ACLs, ingestion/index jobs, embedding-space identity, index generations, and the
active-generation pointer. MySQL also owns the production lexical projection.

Chroma is a derived dense index. Neither Chroma records nor lexical projection rows are the source
of document truth. No external index write is considered atomic with a MySQL transaction. External
writes are driven by durable, leased jobs; retries are idempotent; publication occurs only after
verification.

The existing Hibernate-created `knowledge_chunks` table remains a legacy compatibility store during
migration. New P12 tables use distinct names and versioned Flyway migrations. Old migrations and the
legacy table are not rewritten in place.

### 2. Identity and immutable versions

- `documentId` is an application-generated UUIDv7 created once for a logical document.
- `contentHash` is SHA-256 of canonical extracted UTF-8 text bytes. Extraction policy and parser
  version are separately recorded; raw files are not silently treated as canonical text.
- `documentVersionId` is `dv1-` plus lower-case SHA-256 of canonical JSON containing `documentId`,
  `contentHash`, canonical metadata hash, extraction policy version, and source revision. Repeating
  the same input is idempotent; any material change creates a new immutable version.
- `chunkId` is `kc1-` plus lower-case SHA-256 of canonical JSON containing `documentVersionId`,
  chunker identity/version, zero-based ordinal, character offsets, and chunk-content hash.
- `citationId` is an opaque canonical identifier derived from document, version, and chunk IDs; it
  never relies on mutable titles or URLs.

Canonical JSON uses UTF-8, lexicographically ordered object keys, preserved array order, decimal
numbers without exponent notation, and no insignificant whitespace. IDs and hashes are stored and
compared case-sensitively. A version and its chunks are append-only.

### 3. Lexical branch

Production lexical search uses MySQL 8 InnoDB `FULLTEXT ... WITH PARSER ngram` in natural-language
mode. The deployment contract pins `ngram_token_size=2`; startup readiness rejects a production
database with a different value. This is an indexed, Chinese-capable implementation with no new
service. Raw `MATCH ... AGAINST` relevance is branch-local and is never arithmetically compared with
cosine similarity.

Flyway will use vendor-specific locations. The MySQL migration creates the ngram FULLTEXT index; the
H2 migration creates only structurally compatible test tables. H2 is not accepted as evidence for
MySQL tokenization/ranking. MySQL Testcontainers tests are mandatory for the lexical adapter.

### 4. Dense branch and compatibility

The target dense backend is Chroma `1.5.9`, using its REST v2 tenant/database/collection API. P12.4
must pin both the release tag and immutable image digest in the deployment manifest, verify the
server version at startup, resolve collections by UUID, and reject unsupported versions. This ADR
does not claim the current v1 gateway is compatible.

The application generates both document and query embeddings through one versioned
`EmbeddingSpace` contract. Chroma receives explicit embeddings and must not choose its own embedding
function. An embedding space fixes provider class, model, model revision when known, dimension,
normalization, distance metric, and preprocessing version. A dimension or identity mismatch fails
the job/readiness check; it never silently mixes vectors.

One collection is used per embedding space. Record ID is `generationId:chunkId`; metadata includes
generation, scope, corpus, document, version, and lifecycle fields for coarse filtering. Every dense
candidate is re-authorized against MySQL, because Chroma is not an authorization authority.

### 5. Generation capability and atomic publication

Generation lifecycle state and immutable capability are separate dimensions. Capability is assigned
when the generation is planned and is part of generation identity and verification provenance:

- `LEXICAL_ONLY` requires a complete MySQL lexical projection, exact manifest membership and content
  hashes, current ACL/lifecycle enforcement, and a verified lexical configuration. Dense records are
  neither required nor implied.
- `HYBRID` requires every `LEXICAL_ONLY` guarantee plus the exact target-generation Chroma point set,
  matching chunk/content metadata, the declared `EmbeddingSpace`, vector dimensions, collection UUID,
  and dense publication provenance.

`READY` means every requirement of the declared capability passed; `ACTIVE` means only that the
authoritative pointer selected the generation. `ACTIVE` does not imply `HYBRID`, and capability may
not be upgraded in place. P12.3 may therefore publish a valid `LEXICAL_ONLY` generation. P12.4 builds
and publishes a new `HYBRID` generation rather than adding vectors to that generation.

An index generation is immutable after it leaves `BUILDING`. A capability-aware builder writes and
verifies all projections required by that capability. Counts are diagnostic only: READY requires
exact expected/actual identity sets and hashes.

Activation is a compare-and-swap MySQL transaction on one active-generation pointer per corpus. A
retrieval reads that pointer once and binds both branches to it. A generation is never inferred from
"latest" rows. Rollback is another pointer swap to a retained `READY` generation. Old generations
are retained through the configured recovery/citation grace period and deleted only by an audited
garbage-collection job when no retention hold remains.

There remains one active pointer per corpus. `LEXICAL_ONLY` retrieval accepts either capability.
`HYBRID_REQUIRED` accepts only `HYBRID`; a capability mismatch returns zero evidence with
`UNAVAILABLE/GENERATION_CAPABILITY_MISMATCH`. A separately trusted
`HYBRID_ALLOW_LEXICAL_DEGRADE` mode may use an active `LEXICAL_ONLY` generation and must return
`DEGRADED` with that reason. A client/model cannot choose the degradation policy. Rollback tooling
must validate the destination capability against the configured retrieval mode before the pointer
swap. Historical Recovery keeps the original generation and capability binding.

### 6. Deletion and revocation

Deletion is soft retirement in the control plane. Revocation changes authoritative visibility
immediately and invalidates new retrieval even before a replacement generation is published. Both
lexical SQL and the dense post-filter join current MySQL document/version/ACL state. Physical index
cleanup is asynchronous and is not the security boundary.

Historical Runtime context snapshots and durable tool results remain immutable. They are not edited
or rerun after revocation. Citation resolution reports that the source was revoked and withholds any
newly protected body; it does not pretend the historical run never saw it.

### 7. Trusted scope and ACL

`RetrievalPrincipal` is constructed only from authenticated server identity and policy. It contains
user identity, roles, allowed scope/corpus IDs, and a policy version. The student and model may
provide query text and bounded `topK`; they cannot provide principal, ACL, document IDs, generation,
or arbitrary filters.

Each branch applies generation, scope, lifecycle, and ACL predicates. Chroma filters are performance
hints only; MySQL post-authorization is mandatory before fusion. An authorization/control-plane
failure fails closed. The current single-institution deployment uses an explicit `campus-default`
scope rather than an implicit global scope.

The current `AgentTool` method does not receive `AgentRuntimeContext`, although ToolExecutor already
has it. P12.5 therefore adds a backward-compatible contextual-tool adapter that passes the existing
runtime context to tools that opt in. Existing tools and `AgentTool` behavior remain unchanged.
`KnowledgeSearchTool` is migrated to that adapter so `userId`, run identity, and scope are injected
server-side; they are not added to model-visible `KnowledgeSearchInput`.

### 8. Retrieval and fusion

Lexical and dense branches independently return `candidateK` authorized candidates. Within a branch,
ordering is raw score descending then `chunkId` ascending. Candidates are deduplicated by exact
`chunkId` and fused with Reciprocal Rank Fusion:

`RRF(c) = sum over contributing branches b of 1 / (60 + rank_b(c))`

Ranks start at one and missing branches contribute zero. Final pre-rerank order is: exact RRF score
descending, best branch rank ascending, contributing-branch count descending, then `chunkId`
ascending using unsigned UTF-8 byte lexicographic order. Implementations compare RRF as an exact
reduced rational using arbitrary-precision integers; a scale-18 `HALF_EVEN` decimal is display/
serialization only and never drives ordering. Authorization precedes branch rank assignment.
Deduplication uses `(generationId, chunkId)`. Hash-map iteration, branch completion, and backend
arrival order cannot affect results. This paragraph defers to the normative algorithm in
`docs/p12/retrieval-contract.md` and keeps uncalibrated raw scores separate.

An optional reranker operates only on a bounded fused set. It is disabled by default. A model-based
reranker must use the existing Model Gateway and a declared Runtime/application budget; it may not
call `AiClient` directly. It has its own deadline and falls back to exact RRF order on timeout or
failure. Its score is separate from raw and fusion scores.

### 9. Failure and evidence behavior

The default contract has a configurable overall deadline and smaller branch deadlines; proposed
initial values are 1,500 ms overall, 600 ms per branch, and 400 ms for optional reranking. These are
engineering defaults to measure in P12.6, not observed performance.

One failed/timed-out retrieval backend yields a typed `DEGRADED` result from the other branch. Both
branches failing yields `UNAVAILABLE`, with no fabricated evidence. No matches yields `EMPTY`.
ACL uncertainty fails closed and is not a degradable backend failure. Insufficient/stale/revoked
evidence is excluded; the caller must abstain from unsupported knowledge claims.

Evidence is typed and includes document/version/chunk/citation/generation identity, source metadata,
exact untrusted content, branch ranks and raw scores, fusion/reranker scores, embedding-space
identity, and degradation information. Logs contain identities, counts, timings, and reason codes,
not query text or chunk content by default.

### 10. Runtime integration and recovery

There are exactly two knowledge-use modes:

1. **Pre-admission retrieval** is chosen by trusted application policy for a fresh request that needs
   one deterministic evidence lookup. The exact typed result is encoded through
   `UntrustedEvidenceContextSource` and frozen in the initial context snapshot before admission.
   It performs no auxiliary model planning/review.
2. **Governed Runtime tool retrieval** is used when the model may iteratively decide that knowledge
   is needed. `knowledge_search` remains in the existing Tool Registry and Policy Engine; tool budget,
   durable result, reliable-outcome, and Recovery rules remain P6-P10 owned.

A fresh request selects one primary mode; it does not silently execute both. Tool retrieval binds a
generation in trusted execution metadata before the attempt. A known durable result is materialized
on Recovery. An unknown retry may query only the retained bound generation. Recovery never resolves
the current active generation and substitutes newer evidence for a historical attempt.

`AgenticRagService` remains behind a rollback flag through P12.5. Its direct auxiliary LLM plan/review
calls are not part of the target. They are removed from the migrated path or, if a later benchmark
justifies equivalent functions, reintroduced only through governed Model Gateway calls with budget
ownership. No second RAG agent loop is added.

### 11. High-risk ordering

P12.1H applies this rule immediately to both `ChatMigrationFacade` and legacy `ChatService` rather
than deferring it to P12.5. Classification and assessment determine authoritative risk;
`ToolOrchestrationService.persistReportWithNotificationIntents` atomically persists the report,
AlertRecord, and notification intent and returns only after the transactional proxy commits. Optional
legacy RAG begins afterward.

Retrieval is never a prerequisite for preserving or dispatching a HIGH-risk fact. Only explicitly
classified timeout, backend-unavailable, or transient data-access failures degrade HIGH-risk
retrieval to empty evidence with a safe reason code. Authentication/authorization, integrity,
validation, and unexpected programming failures still propagate. No failure can lower risk or remove
trusted safety policy. The fallback gives only trusted, statically configured general guidance; it
never invents campus contact details. Logs contain reason codes, not student input.

### 12. Evaluation

Retrieval relevance receives a separate, versioned `RetrievalEvalSuite`. It may reuse P9 dataset
provenance, canonical report, and comparator patterns, but P9 `contractPass` remains a Runtime
contract result and is not relabeled as retrieval quality. Default CI is offline and deterministic.
Provider embeddings/rerankers are opt-in baselines. Proposed gates and methodology are defined in
`docs/p12/evaluation-plan.md`; none are claimed as current measurements.

## Alternatives considered

| Decision | Alternative | Reason not selected now |
| --- | --- | --- |
| MySQL ngram FULLTEXT | Current `TokenVectorizer` and full-table scan | Not indexed; cannot provide bounded production retrieval or faithful BM25-style branch ranking. |
| MySQL ngram FULLTEXT | OpenSearch/Elasticsearch | Strong retrieval features, but adds an unnecessary third operational service for the present scale. Reconsider only if measured requirements exceed MySQL. |
| Chroma 1.5.9 REST v2 | Current v1 `ChromaGateway` | Endpoint/version and embedding identity are not verified. |
| Chroma | MySQL-only application cosine scan | O(N), memory-heavy, and not a production vector index. |
| RRF | Weighted raw-score blend | BM25/FULLTEXT relevance and cosine are uncalibrated and not directly comparable. |
| Generation pointer | In-place delete/reinsert by source | Exposes partially rebuilt indexes and prevents atomic rollback. |
| MySQL post-authorization | Trust Chroma metadata filters | The derived index is not an authorization authority; revocation must take effect before cleanup. |
| Contextual tool adapter | Put `userId`/ACL filters in tool arguments | Model/student-controlled fields cannot establish authorization. |
| Frozen evidence on Recovery | Rerun retrieval against latest index | Would mutate historical execution meaning and violate P10/context-snapshot determinism. |

## Consequences

- P12 introduces a durable control plane and a real two-branch Hybrid RAG design without replacing
  the Agent Runtime.
- Publication has eventual build consistency but atomic reader visibility through the MySQL pointer.
- Urgent revocation is secure even while derived cleanup lags.
- MySQL and Chroma compatibility become explicit deployment requirements and integration-test
  obligations.
- The legacy path remains available for bounded rollback until P12.6 gates pass; it is not declared
  production-ready merely because it remains present.

## External compatibility references

- MySQL 8.4 ngram FULLTEXT parser: <https://dev.mysql.com/doc/refman/8.4/en/fulltext-search-ngram.html>
- MySQL InnoDB FULLTEXT search: <https://dev.mysql.com/doc/refman/8.0/en/fulltext-search.html>
- Chroma API reference: <https://docs.trychroma.com/reference/chroma-api>
- Chroma collection API: <https://docs.trychroma.com/reference/js-collection>
- Chroma releases: <https://github.com/chroma-core/chroma/releases>
