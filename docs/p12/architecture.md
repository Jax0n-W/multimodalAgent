# P12 Knowledge and Hybrid RAG Architecture

## Scope and status

This document describes the proposed P12 target and the audited P12.1 baseline at commit
`8df06ff7fc31485ad29ceb5c8f5ef40ffb3cd8e5`. It is architecture, not evidence that P12.2 or later
has been implemented. ADR-027 is the normative decision record.

## Current inventory

| Area | Current repository artifact | Assessment | P12 disposition |
| --- | --- | --- | --- |
| Ingestion | `KnowledgeService.ingest`, `KnowledgeIngestionService`, `KnowledgeFileService` | Source replacement and external writes are not crash-safe; startup `count() == 0` can strand partial ingestion. File extraction is in-memory and lacks durable provenance. | Replace behind compatibility facade. |
| Chunking | `KnowledgeChunker` | Reusable boundary heuristics, but no chunker identity, offsets, canonical hash, or immutable version binding. | Adapt as a versioned chunker. |
| SQL storage | `KnowledgeChunk`, `KnowledgeChunkRepository` | Mutable rows, generated numeric ID, source/index/content/embedding only; table is created by Hibernate, not V1-V12. | Deprecate after dual-read/rollback period; do not mutate in place. |
| Embedding | `EmbeddingClient`, `OpenAiEmbeddingClient` | Useful port, but identity exposes only model name; no dimension/preprocessing/provider revision or explicit timeout. | Replace with versioned `EmbeddingSpace` and batch/deadline contract. |
| Vector index | `ChromaGateway` | Legacy v1 URL shape, server-side text embedding, swallowed failures, and no verified version/dimension/ACL contract. | Replace with a REST v2, version-pinned derived-index adapter. |
| Retrieval | `KnowledgeService.retrieve` | Chooses fallback paths and sometimes scans all rows. Local 75/25 score blending is not two-branch hybrid retrieval. | Replace with independent lexical/dense adapters plus RRF. |
| Result | `SearchResult` | Only numeric chunk ID, source, content, score. | Supersede with typed `RetrievalEvidence`; keep compatibility mapping temporarily. |
| Legacy orchestration | `AgenticRagService`, `AgenticRagResult` | Up to two retrieve/review cycles; auxiliary `AiClient.complete` calls are outside Model Gateway and budget ownership. | Feature-flagged compatibility only, then retire after P12.6 gates. |
| Runtime tool | `KnowledgeSearchTool`, `KnowledgeSearchInput` | Reuses Tool Registry/Policy Engine, but has no trusted retrieval principal/generation binding. | Keep public query/topK shape; add server-bound contextual adapter. |
| Fresh chat | `ChatMigrationFacade` | RAG evidence is already sent through `UntrustedEvidenceContextSource`, but retrieval currently precedes durable HIGH-risk report creation in the non-chat path. | Reorder in P12.5: risk fact/outbox first, then bounded retrieval. |
| Trust boundary | `UntrustedEvidenceContextSource`, `AgentContextAssembler` | Strong reusable P11.4 boundary: canonical non-SYSTEM evidence, deterministic ordering/provenance. | Reuse unchanged; encode typed P12 evidence as untrusted data. |
| Snapshots/recovery | `AgentContextSnapshot`, `ContextAssemblingAgentExecutionCoordinator`, P10 recovery | Fresh context is content-addressed; Recovery resumes checkpoints and does not reassemble context. | Preserve. Never rerun historical retrieval to refresh evidence. |
| Evaluation | P9 eval package, ADR-015, `docs/eval/README.md` | Good deterministic provenance/reporting, but `contractPass` measures Runtime contract behavior, not retrieval relevance. | Reuse infrastructure patterns in a separate retrieval suite. |

No dedicated Knowledge/RAG test class exists in the audited test tree. The reference in
`ToolRecoveryContractTest` proves tool recovery wiring, not relevance, ACL, index publication, or
Chroma compatibility.

## Audited deficiencies and method-level evidence

- `src/main/java/com/multimodalAgent/agent/service/knowledge/KnowledgeService.java`:
  `ingest` deletes by mutable source and calls `ChromaGateway.mirror` per saved row; the external
  operation cannot share the MySQL transaction. `retrieve` selects Chroma *or* database scan
  fallbacks. `hybridScore` is a 0.75/0.25 local blend, not independent sparse/dense candidate fusion.
- `src/main/java/com/multimodalAgent/agent/domain/KnowledgeChunk.java` and
  `repository/KnowledgeChunkRepository.java`: generated `Long` identity and mutable source/index
  lookup provide no stable document, immutable version, deterministic chunk, ACL, generation, or
  embedding-space identity. No V1-V12 script creates the mapped table.
- `service/knowledge/KnowledgeIngestionService.java`: the `repository.count() == 0` startup guard can
  treat a partial prior attempt as complete. `KnowledgeFileService` parses bounded files in memory but
  does not persist extractor/content-hash/version provenance.
- `service/knowledge/OpenAiEmbeddingClient.java`: `modelName` is the only exposed identity; dimension,
  provider/revision, preprocessing, batching, and a complete deadline contract are absent.
- `service/knowledge/ChromaGateway.java`: `/api/v1` name-based endpoints, `documents`/`query_texts`,
  swallowed errors, and `collectionEnsured` do not prove current-server compatibility or shared
  embedding identity. It is not an ACL boundary.
- `service/knowledge/AgenticRagService.java`: `plan` and `review` call `AiClient.complete` directly and
  retrieval may repeat. Those auxiliary calls do not use Model Gateway, execution snapshots, or
  Runtime budget accounting. `AgenticRagResult.contextBlock` and `SearchResult` are string/score
  compatibility types without typed citations.
- `tool/builtin/KnowledgeSearchTool.java`: Tool Registry/Policy wiring is reusable, but `execute`
  forwards only query/topK to `KnowledgeService`; `AgentTool` does not currently receive the
  `AgentRuntimeContext` that ToolExecutor already holds, so trusted user/scope binding is absent.
- `service/chat/ChatMigrationFacade.java` lines 137-159 at this baseline invoke `ragService.retrieve`
  before `saveReport`. That ordering must change in P12.5 because HIGH-risk durable reporting/outbox
  cannot depend on retrieval availability.
- `context/UntrustedEvidenceContextSource.java` and `AgentContextAssembler.java` are positive reusable
  evidence: retrieved data is a canonical non-SYSTEM envelope with deterministic source ordering and
  provenance. `ContextAssemblingAgentExecutionCoordinator` persists that snapshot only for fresh
  execution; P10 Recovery correctly resumes durable snapshots/checkpoints instead of rebuilding it.

## Target component model

```text
Admin/API source
    -> KnowledgeIngestionApplicationService
       -> MySQL catalog (document, version, ACL, chunks, jobs) [authority]
       -> durable index-build task
              |-> MySQL ngram lexical projection [derived, generation-bound]
              `-> EmbeddingGateway -> Chroma v2 [derived, generation-bound]
       -> verifier -> READY
       -> MySQL compare-and-swap active-generation pointer

Trusted RetrievalPrincipal + query
    -> RetrievalCoordinator (bind active generation once, enforce deadline)
       |-> LexicalRetriever ---- authorized candidates --\
       `-> DenseRetriever -> MySQL re-authorization ----+-> RRF -> optional reranker -> evidence

Fresh-request mode -> canonical evidence -> UntrustedEvidenceContextSource -> Context Snapshot
Runtime-tool mode  -> existing Tool Registry/Policy/Budget -> durable ToolResult/Recovery
```

MySQL is both data authority and publication coordinator. Chroma availability cannot change which
generation is authoritative. The coordinator does not call a model; query expansion and answer
generation are outside the retrieval engine.

## Knowledge lifecycle

1. Authenticate administrator/source connector and normalize a stable logical source key.
2. Extract canonical text with a recorded extractor identity. Reject unsupported or oversized input
   before creating an index job; never ingest conversation transcripts by default.
3. Create/reuse `documentId`; compute deterministic `documentVersionId`. If it already exists, return
   the existing result without creating duplicate chunks or external work.
4. Chunk deterministically and transactionally append the immutable version/chunk records plus a
   durable build task. The commit contains no Chroma call.
5. A leased worker creates a `BUILDING` generation or contributes the version to an explicit build.
   Every external operation uses deterministic record IDs and can be retried.
6. Verify exact expected/actual chunk-ID sets, counts, hashes, embedding space, dimension, and lexical
   projection. Failed verification marks the job/generation `FAILED`; the previous active generation
   remains live.
7. Mark `READY`, then atomically compare-and-swap the corpus pointer to `ACTIVE`. A conflict causes a
   reread/replan, never a blind overwrite.
8. Retain the previous generation for rollback. Retirement/revocation changes control-plane
   visibility immediately; cleanup happens later.

Incremental update means reusing unchanged immutable versions/chunks and deterministic external
records while building a complete new generation manifest. It does not mean modifying an active
generation in place.

## Retrieval paths

### Mode A: pre-admission frozen evidence

Use for a fresh request when trusted product routing knows that one bounded lookup is required before
answer generation. The application constructs `RetrievalPrincipal` and runs deterministic retrieval
without an LLM. The full result (including status and failures) is canonicalized into
`UntrustedEvidence(kind=knowledge-retrieval-v1, source=hybrid-rag/{generationId}, content=...)`.
`AgentContextAssembler` then freezes it in the initial snapshot. A retrieval failure is represented
as status metadata, not a fabricated document.

For HIGH risk, assessment and the transaction that records the report plus durable notification
intent happen first. Retrieval may enrich the response, but cannot block or undo safety processing.

### Mode B: governed Runtime tool

Use when retrieval is conditional or iterative. The model-visible input remains query plus bounded
topK. An additive contextual-tool adapter obtains the already-existing `AgentRuntimeContext`, resolves
trusted scope from `userId`, and durably binds `generationId` to the tool attempt. The existing Tool
Registry, policy decision, tool-call budget, events, reliable outcome, and checkpoints remain in
charge. The adapter does not add another agent loop.

If a durable result exists, Recovery materializes it. If a retry is permitted after an unknown
outcome, it uses the same bound generation and deterministic request identity. If that generation is
no longer retained, the attempt fails explicitly with `BOUND_GENERATION_UNAVAILABLE`; it does not
substitute the new active generation.

## Publication and consistency rules

- A query observes exactly one generation per corpus.
- Both branches use that generation, even while a new generation is building.
- `READY` means complete and verified; `ACTIVE` means selected by the authoritative pointer.
- Chroma or lexical leftovers from uncommitted/failed builds are invisible because they lack the
  active generation binding and current MySQL authorization.
- A successful pointer swap is the publication linearization point.
- Crash before swap leaves the old generation active. Crash after swap is safe because verification
  already completed and the pointer is durable.
- Repeated job delivery performs idempotent upsert/check operations and cannot create a second
  version or duplicate vector record.
- Embedding-space change always builds a new generation. In-place re-embedding is forbidden.

## Security and privacy controls

- Source management remains an administrator operation; source connectors receive least privilege.
- `scopeId`, corpus allow-list, principal, ACL predicates, and generation come from server policy, not
  request JSON or tool arguments.
- Lexical candidates are filtered in SQL. Dense metadata prefilters reduce work, then every candidate
  is checked against current MySQL document state and ACL before content is returned or fused.
- A revoked document is denied immediately, even if its vector remains in Chroma.
- Evidence is always a non-SYSTEM untrusted-data envelope. Stored document text cannot alter tool
  policy, risk policy, or system instructions.
- Query text and evidence content are excluded from default application logs, metrics labels, and
  tracing attributes. Audit logs store IDs, versions, counts, durations, result status, and reason
  codes. Debug content logging is not a production option.
- Conversation messages and student uploads are not knowledge sources unless a separate, explicit,
  consented retention workflow is approved in a future phase.
- Citation endpoints repeat current authorization and lifecycle checks; knowledge URLs are not bearer
  capabilities.

## Failure behavior

| Failure | Result | Safety/consistency behavior |
| --- | --- | --- |
| Lexical timeout, dense succeeds | `DEGRADED` | Return authorized dense evidence; record lexical reason. |
| Dense embedding/Chroma timeout, lexical succeeds | `DEGRADED` | Return authorized lexical evidence; no local full-table cosine fallback. |
| Both branches fail | `UNAVAILABLE` | Return no evidence; caller abstains from knowledge-specific claims. |
| No authorized match | `EMPTY` | No evidence; do not reveal whether unauthorized matches exist. |
| ACL/control-plane unavailable | `DENIED_UNVERIFIED` | Fail closed; do not treat as ordinary partial success. |
| Generation changes mid-query | Bound generation remains | Both branches and citations keep the generation read at start. |
| Index build fails | Old generation remains active | Job is retryable/quarantined by reason; no partial publication. |
| Revocation races retrieval | Final MySQL authorization wins | Candidate removed; result may contain fewer than topK. |
| Reranker fails/times out | Exact RRF fallback | Reranker cannot make retrieval unavailable. |
| HIGH-risk retrieval fails | Safety flow continues | Preserve risk/report/outbox; use trusted generic fallback, never invented contacts. |

## Legacy migration boundary

P12.2-P12.4 build the new system behind disabled flags while `AgenticRagService` remains available.
P12.5 introduces shadow comparison, then switches fresh pre-admission retrieval and
`knowledge_search` independently. Rollback flags choose a whole path; they never merge incompatible
legacy/new scores. The legacy direct `AiClient.complete` planner/reviewer is not copied into the new
engine. P12.6 may deprecate it only after quality, isolation, recovery, and operational gates pass.

## Deployment compatibility

- MySQL 8 with InnoDB FULLTEXT and `ngram_token_size=2` is required for production lexical search.
- H2 supports contract/unit tests only. MySQL Testcontainers validates migration and ranking behavior.
- Chroma server compatibility is restricted to the pinned 1.5.9 release/digest until another version
  passes the same adapter suite. Startup validates `/api/v2/version` and collection metadata.
- Provider-dependent embeddings are optional in CI. Production readiness requires an explicit
  embedding space and dimension check before a generation can become READY.
