# P12.2-P12.6 Implementation Roadmap

## Delivery rules

Each increment is independently reviewable and defaults OFF until its acceptance gate passes. Work
must begin from the then-current Git truth, preserve unrelated changes, and not edit V1-V12. No phase
may declare a class name or happy-path test to be production readiness. P6-P11 Runtime, budget,
snapshot, recovery, chat-history, SSE, and safety invariants remain regression gates throughout.

## P12.2 — Durable Knowledge Catalog and Ingestion Lifecycle

### Scope

Implement the authoritative catalog, deterministic identities, immutable versions/chunks, ACL model,
versioned extraction/chunking, fenced ingestion jobs, and idempotent admin ingestion API. Adapt
`KnowledgeChunker` only after freezing its configuration/version; replace direct
`KnowledgeService.ingest` side effects behind a disabled P12 facade. No retrieval traffic switches.

### Dependencies

- Approved ADR-027 and `docs/p12/data-model.md`.
- Existing administrator authentication at `/api/admin/**` (do not modify the user's current
  `SecurityConfig` as part of P12.1).
- MySQL/Flyway and explicit source/extractor limits.

### Interfaces

- `KnowledgeCatalog`, `KnowledgeIngestionServiceV2`, `CanonicalTextExtractor`, `VersionedChunker`.
- `IngestionRequest` with connector-scoped idempotency key, logical source key, metadata, and payload
  reference; `IngestionReceipt` with document/version/job/status.
- `KnowledgeAuthorizationPolicy` for trusted ACL mutations.
- Admin status/retry/retire operations; no student-facing document selectors.

### Migrations

- V13 creates `kb_corpora`, `kb_documents`, `kb_document_acl`, `kb_document_versions`, `kb_chunks`,
  `kb_ingestion_jobs`, `kb_embedding_spaces`, `kb_index_generations`, `kb_generation_members`,
  `kb_active_generations`, `kb_index_tasks`, and `kb_generation_audit` plus constraints/indexes.
- V13 seeds only configuration-owned corpus/scope records. It neither reads nor rewrites legacy
  `knowledge_chunks`.
- Add vendor-location configuration only if needed by the next phase; do not add a fake empty version.

### Exact invariants

1. One logical source key maps to one stable UUIDv7 document ID.
2. The same canonical identity payload returns the same immutable version and chunks.
3. A changed content/identity payload creates a new version; no old version/chunk is updated.
4. Catalog commit and ingestion job creation are one MySQL transaction with no external call.
5. Every claimed job transition matches expected status, owner, and unique claim token.
6. Student conversations/uploads are not accepted sources by default.
7. Retirement/revocation changes authoritative visibility immediately and is auditable.
8. Logs never contain raw source bodies, secrets, or student-sensitive fixtures.

### Required tests

- Canonical JSON/hash/UUID/chunk golden tests, Unicode NFC/line-ending cases, and restart repeatability.
- Same request/same payload idempotency; same key/different payload conflict.
- Concurrent first ingestion and concurrent version update.
- Crash before/after catalog commit, claim expiry, stale-claim fencing, and retry exhaustion.
- Immutable-column/repository tests and ACL mutation audit tests.
- Fresh and populated V12-to-V13 MySQL migration; H2 structural test.
- File size/media/extractor failure and malicious filename/metadata cases.

### Failure scenarios

Extraction crash, unsupported media, malformed UTF-8, duplicate delivery, process death after commit,
lease expiry with same worker reclaim, hash collision guard, database deadlock, and revocation racing an
ingestion attempt.

### Acceptance Gate

All required tests pass with zero stale-claim transitions; migration is reversible by application
rollback (schema may remain unused); repeated fixtures produce byte-identical identities; security
review confirms no external index call occurs in the catalog transaction. Feature remains OFF and
legacy retrieval output is unchanged.

### Explicit exclusions

MySQL FULLTEXT, Chroma v2, query embeddings, hybrid fusion, Runtime integration, legacy data auto-copy,
real student data, relevance claims, and removal of legacy classes.

## P12.3 — Lexical Index, Generation Publication, and Revocation

### Scope

Implement MySQL 8 ngram FULLTEXT projection, generation manifests, durable build/verify tasks,
compare-and-swap activation/rollback, retention-safe cleanup, and lexical-only retrieval behind an
internal flag. Establish the publication protocol before adding a second backend.

### Dependencies

- P12.2 catalog/ACL/job fencing accepted.
- MySQL 8 environment with documented `ngram_token_size=2`.
- Approved deterministic lexical query normalization and candidate limits.

### Interfaces

- `LexicalIndexWriter`, `LexicalRetriever`, `GenerationPlanner`, `GenerationVerifier`,
  `GenerationPublisher`, `GenerationRetentionPolicy`.
- `BranchCandidate` and typed backend failure from `retrieval-contract.md`.
- Readiness probe exposing only compatibility/status identities and reason codes.

### Migrations

- Configure Flyway common plus `{vendor}` locations.
- MySQL V14 creates `kb_lexical_projection` with InnoDB ngram FULLTEXT and generation/filter indexes.
- H2 V14 creates a structurally compatible table without claiming FULLTEXT behavior.
- V1-V13 are not edited. MySQL migration is tested from an actual V13 schema.

### Exact invariants

1. Production readiness fails if engine/parser/token size is incompatible.
2. A BUILDING generation is invisible to readers.
3. Manifest exact-set/hash verification is required before READY.
4. Only READY can win active-pointer CAS; exactly one concurrent publisher wins.
5. A query binds one generation once and authorizes in MySQL before returning content.
6. Raw lexical relevance never leaves its branch field or mixes with another score.
7. Revocation/ACL removal denies new results before projection cleanup.
8. Cleanup never targets active or retained rollback generations.

### Required tests

- MySQL Chinese/exact/mixed-language ngram cases and parameterization/injection tests.
- Deterministic score-tie `chunkId` ordering and candidateK bounds.
- ACL scope/role/user allow/deny joins, unauthorized high-relevance negative.
- Partial build, count-equal/set-different, content-hash mismatch, failed verification.
- Concurrent CAS activation, crash immediately before/after swap, rollback, and cleanup race.
- Startup with wrong token size/version; H2 explicitly cannot satisfy production readiness.
- Revocation during query/final authorization and stale projection content.

### Failure scenarios

Database timeout, deadlock, partial batch, worker death, corrupted projection, wrong parser config,
concurrent publisher, pointer conflict, revoked document remaining in projection, and cleanup crash.

### Acceptance Gate

Required MySQL Testcontainers suite runs without skips; generation exact-set and concurrency tests are
100%; zero ACL/revocation leakage; crash matrix preserves either the old complete or new complete
generation; lexical flag remains internal and legacy user behavior remains unchanged.

### Explicit exclusions

Chroma, embeddings, RRF, reranking, answer generation, Runtime tool switching, automatic legacy data
deletion, and production traffic cutover.

## P12.4 — Versioned Dense Index and Hybrid Retrieval Engine

### Scope

Replace the current v1 `ChromaGateway` for P12 traffic with a pinned REST v2 adapter, implement
`EmbeddingSpace`/deadline/batch contracts, dense publication and verification, independent parallel
branches, RRF, typed evidence/citations, and partial-backend failure behavior. Keep callers behind a
disabled/shadow feature flag.

### Dependencies

- P12.3 generation/publisher and lexical adapter accepted.
- Chroma 1.5.9 tag plus recorded immutable image digest.
- A deterministic offline embedding fixture and an explicitly configured production embedding space.

### Interfaces

- `EmbeddingGateway`, `EmbeddingSpace`, `DenseIndexWriter`, `DenseRetriever`, `ChromaV2Client`.
- `RetrievalCoordinator`, `RetrievalRequest`, `RetrievalResult`, `RetrievalEvidence`,
  `RetrievalFailure`, `CitationResolver`.
- Optional `EvidenceReranker` port, implemented as OFF/no-op in this phase unless separately approved.

### Migrations

- Add V15 only if implementation needs authoritative collection binding/publication columns not
  already present in V13. Store collection UUID/version/digest/config hashes, never credentials.
- A reviewed finding of “no schema change required” is valid; do not create an empty migration.
- No changes to old migrations or legacy Hibernate table.

### Exact invariants

1. Application-generated explicit vectors are used for both indexing and querying.
2. Model/revision/dimension/preprocessing/metric mismatch fails before READY/query.
3. Chroma server version and collection UUID are verified; name alone is not a data-plane identity.
4. Vector point ID is deterministic `generationId:chunkId`; retry is idempotent.
5. Every dense candidate is manifest/hash/lifecycle/ACL validated in MySQL before fusion/content.
6. Lexical and dense branches use the same bound generation.
7. RRF k=60 and the total tie order are exact; raw scores remain separate.
8. One trustworthy branch may yield `DEGRADED`; ACL uncertainty yields zero evidence.
9. Both-backend failure and no-answer return no fabricated evidence.
10. Default logs/metrics contain no query, body, vector, or provider payload.

### Required tests

- Chroma v2 path/version/collection UUID contract against pinned container.
- Explicit vector/dimension, metric, metadata filter, deterministic ID, upsert/retry, and timeout tests.
- Dense stale point, unauthorized high-score hit, wrong hash, and missing manifest membership.
- RRF golden/property tests: dedupe, raw-score isolation, deterministic ties, branch permutations.
- Concurrent branch deadline and every partial/failure combination.
- Generation re-embedding, new embedding space, failed build, activation, rollback, retained old query.
- Typed evidence/citation canonicalization and current-authorization resolution.
- Shadow comparison records only identities/ranks/status, never sensitive content.

### Failure scenarios

Unsupported Chroma version, collection lookup ambiguity, server embedding accidentally enabled,
dimension mismatch, provider timeout/rate limit, partial point upsert, stale/extra points, lexical-only
degradation, dense-only degradation, both unavailable, ACL database loss, and deadline cancellation.

### Acceptance Gate

Pinned-container and MySQL integration suites pass without required skips; exact generation/embedding
compatibility is proven; zero ACL/revocation leaks; canonical repeated results are identical in the
offline profile; injected failures match the contract table; no production/chat cutover occurs.

### Explicit exclusions

Model answer quality, auxiliary LLM query planning/review, default-on reranking, AgentRunner changes,
fresh-chat reordering, legacy removal, production traffic, and P13.

## P12.5 — Runtime/Safety Integration and Controlled Legacy Migration

### Scope

Integrate the accepted engine in the two declared modes, add a backward-compatible contextual-tool
adapter, freeze pre-admission evidence into existing untrusted context, bind Runtime tool generation/
principal, reorder HIGH-risk safety persistence before retrieval, and perform flag-controlled rollout
from `AgenticRagService`. Preserve external chat/SSE/history and all P10 Recovery semantics.

### Dependencies

- P12.4 contract/integration gates accepted.
- ADR-022, ADR-025, ADR-026 and P10 reliable-outcome/recovery tests.
- Trusted mapping from `AgentRuntimeContext.userId` to scope/corpus/roles.

### Interfaces

- `KnowledgeRetrievalApplicationService` for pre-admission mode.
- Additive `ContextualAgentTool<I,O>` (or equivalently reviewed adapter) receiving the existing
  `AgentRuntimeContext`; existing `AgentTool`, registry, policy, and results remain compatible.
- Migrated `KnowledgeSearchTool` whose public input is still query/topK only.
- Durable `RetrievalAttemptBinding` attached to existing tool-execution identity: run, toolCall,
  principal-policy hash, generation, request hash. It is supporting metadata, not a second outcome
  truth.
- Feature flags independently select legacy/new pre-admission and legacy/new tool paths; cohort is
  auditable.

### Migrations

- Prefer no migration if binding fits an existing durable tool-execution metadata extension safely.
- If schema is required, add the next new version only for immutable retrieval-attempt binding with a
  unique tool-execution foreign identity. Do not alter P10 outcome/checkpoint columns or semantics.
- No migration edits, no second run/admission table, and no knowledge content in Runtime metadata.

### Exact invariants

1. Single Agent Runtime, Model Gateway, Tool Registry, Policy Engine, budget, and admission path.
2. Pre-admission evidence is canonical untrusted ASSISTANT data and part of the immutable initial
   context snapshot; never SYSTEM authority.
3. Current user request remains exactly one USER message.
4. Model/client cannot set user, ACL, corpus, generation, or principal-policy version.
5. Tool permission/budget is evaluated before retrieval; successful result follows existing durable
   outcome/checkpoint rules.
6. Recovery never calls pre-admission retrieval or chooses a new generation for historical work.
7. Unknown tool retry, when P10 permits it, uses the durable original binding or fails closed.
8. Same run ID, no second admission/RUN_STARTED, unchanged config/context snapshots on Recovery.
9. HIGH-risk report plus notification intent commits before any retrieval call; retrieval failure
   cannot erase/delay dispatch eligibility or trusted safety policy.
10. SSE event order and chat-history projection remain ADR-025 compatible.
11. No direct auxiliary `AiClient.complete` planning/review exists on the new path.

### Required tests

- Context ordering/roles/provenance/snapshot hash with malicious retrieved instructions.
- Student/model attempts to select another user, corpus, document, generation, or ACL.
- Real `RecoveryEngine` paths for durable result materialization and bound unknown retry; assert no
  second lookup for known result and no current-generation substitution.
- Tool budget/policy deny, timeout, partial result, cancellation, and reliable-outcome crash windows.
- Same run/no second admission/no second RUN_STARTED/original snapshots unchanged.
- HIGH-risk trace: report + fenced outbox intent committed before a hung/failed retriever; generic
  trusted fallback contains no fabricated campus data.
- External SSE schema/order and exactly one terminal event; chat history final answer equality.
- Feature-flag fresh-session/legacy-session routing and rollback without mixed evidence.

### Failure scenarios

Missing authenticated user in runtime context, scope resolver outage, bound generation retired too
early, crash before/after retrieval outcome durability, malicious evidence, retrieval deadline,
context persistence failure, model failure after safety commit, flag rollback mid-session, and legacy
planner outage.

### Acceptance Gate

All P6-P11 regression suites plus the integration tests above pass; zero authority/ACL leaks; real
Recovery proves exact historical behavior; HIGH-risk ordering is deterministic; no public API/SSE
break; new path can be disabled without data rollback; rollout starts shadow/canary only.

### Explicit exclusions

Second RAG agent runtime, changing Model Gateway pricing/budget semantics, changing P10 disposition
rules, re-running historical context, dynamic client filters, automatic legacy deletion, and P13.

## P12.6 — Retrieval Evaluation, Operational Hardening, and Final Cutover Gate

### Scope

Implement the separate offline `RetrievalEvalSuite`, authorized dataset/manifest, comparison reports,
required dashboards/alerts/runbooks, load/failure/recovery drills, canary rollout evaluation, and a
reviewed decision to retain or deprecate legacy RAG. Optional reranking may be enabled only if its
own gate passes.

### Dependencies

- P12.5 security/runtime integration accepted.
- Authorized synthetic dataset and proposed gates reviewed.
- CI runners for MySQL and pinned Chroma containers; provider profile remains opt-in.

### Interfaces

- `RetrievalEvalCase`, `RetrievalEvalDataset`, `RetrievalEvalTarget`, `RetrievalEvalReport`, compatible
  baseline comparator keyed by every contract/config identity.
- Operational health for ingestion lag, generation state, active pointer age, backend compatibility,
  failure/degraded rates, latency, ACL-filter/mismatch counts, and retained-generation pressure.
- Admin generation activate/rollback/retry/quarantine operations with audit records.

### Migrations

- No migration is expected. Any operational persistence discovered necessary requires a separately
  reviewed new version and data-retention/privacy analysis; reports themselves are build artifacts,
  not production knowledge tables.

### Exact invariants

1. P9 Runtime `contractPass` is unchanged and never presented as retrieval relevance.
2. Default CI uses no provider and no real student-sensitive data.
3. Baselines with incompatible dataset/config/index identities cannot compare as PASS/FAIL.
4. Security, ACL, revocation, citation, generation, Recovery, and HIGH-risk gates are absolute; a
   quality average cannot compensate for one violation.
5. Provider and deterministic results remain separate.
6. Rollback returns traffic to the last accepted complete path/generation without rebuilding in place.
7. Legacy code is removed only after the independent P12 final gate and rollback-period evidence.

### Required tests

- Every dataset family and metric in `evaluation-plan.md`.
- Offline lexical/dense/hybrid and optional reranker comparison.
- Repeatability, baseline incompatibility, report canonicalization, and sensitive-field redaction.
- Load/deadline/connection-pool tests, restart during build, Chroma/MySQL loss, and recovery drills.
- Generation rollback and feature-flag legacy rollback under canary traffic.
- Retention cleanup with active recovery/citation holds.
- Full Maven suite, MySQL migrations, pinned Chroma adapter, and `git diff --check`.

### Failure scenarios

Quality regression, Chinese/no-answer regression, p95 deadline breach, embedding/provider drift,
baseline identity mismatch, skipped container tests, degraded-rate spike, stuck BUILDING generation,
retention exhaustion, rollback failure, ACL/revocation leak, prompt-injection authority violation, and
HIGH-risk ordering violation.

### Acceptance Gate

An independent review confirms every proposed gate in `evaluation-plan.md` or explicitly approves a
versioned change backed by measurements. Required integration tests have no skips, security leak count
is zero, Recovery/safety traces pass, canary and rollback drills succeed, and operational ownership is
documented. Only then may the new engine become default and a later cleanup commit deprecate legacy
`AgenticRagService`/`SearchResult` paths.

### Explicit exclusions

Invented performance claims, real student evaluation data, default provider calls in CI, premature
legacy deletion, generalized workflow/Saga infrastructure, changes to accepted Runtime semantics,
and any P13 work.

## Cross-phase review checklist

Every P12.2-P12.6 review must report: baseline/head, dirty workspace preservation, changed files,
migrations, contract deltas, test counts/skips, Docker/provider profiles actually run, security and
Recovery evidence, feature-flag default, rollback procedure, open risks, and explicit PASS/FAIL. No
phase may stage, commit, or push unrelated user files.
