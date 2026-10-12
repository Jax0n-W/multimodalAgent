# P12 Hybrid Retrieval Contract

## Contract boundary

This contract supersedes `KnowledgeService.retrieve`/`SearchResult` for the P12 engine. A temporary
adapter may project new evidence back to `SearchResult`, but the legacy type is not the source of
truth. Retrieval does not generate an answer and does not run an agent loop.

## Trusted request

Conceptual Java records (names may be refined without changing semantics):

```java
record KnowledgeQueryInput(String query, Integer topK) {} // model/client visible

record RetrievalPrincipal(
    long userId,
    Set<String> roles,
    Set<String> allowedScopeIds,
    Set<String> allowedCorpusIds,
    String policyVersion
) {}

record RetrievalRequest(
    String requestId,
    String query,
    int topK,
    int candidateK,
    RetrievalPrincipal principal,
    Set<String> trustedCorpusFilter,
    String boundGenerationId,
    GenerationCapability requiredCapability,
    CapabilityFallback capabilityFallback,
    Instant deadline,
    RetrievalMode mode,
    String contractVersion
) {}
```

Only `query` and `topK` originate from `KnowledgeSearchInput` or the current user request. The server
normalizes length/control characters and clamps `topK` to 1..20. `candidateK` is server configured,
at least `topK`, and bounded. Principal, corpora, generation, deadline, mode, and policy version are
derived from authenticated/server state. Unknown client JSON fields are rejected; document/version/
chunk IDs and raw ACL filters are not accepted search inputs.

`boundGenerationId` and its immutable capability are read together from `kb_active_generations` plus
`kb_index_generations` for a fresh call or restored from durable tool execution metadata for Recovery.
They are never chosen by the model. Trusted modes are:

- `LEXICAL_ONLY`: accepts an active LEXICAL_ONLY or HYBRID generation and runs only lexical search;
- `HYBRID_REQUIRED`: accepts HYBRID only; mismatch returns
  `UNAVAILABLE/GENERATION_CAPABILITY_MISMATCH` with zero evidence; and
- `HYBRID_REQUIRED` with `ALLOW_LEXICAL_DEGRADE`: a trusted server policy may accept LEXICAL_ONLY,
  run lexical only, and return `DEGRADED/GENERATION_CAPABILITY_MISMATCH`.

The public request cannot choose fallback. There is one active pointer; capability compatibility is
validated after binding and before either backend executes.

## Branch contracts

```java
record BranchCandidate(
    String chunkId,
    RetrievalBranch branch,
    int rank,
    BigDecimal rawScore,
    String generationId,
    String contentHash
) {}
```

Each branch must:

1. enforce the same bound generation and trusted scope/corpus allow-list;
2. return at most `candidateK` candidates;
3. sort raw score descending and `chunkId` ascending for exact ties;
4. number ranks from one after branch-local authorization/filtering;
5. never return content until current MySQL lifecycle and ACL authorization succeeds; and
6. report timeout, unavailability, compatibility, and consistency failures as typed reason codes.

### Lexical

The adapter executes parameterized MySQL `MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE)` over
the ngram FULLTEXT projection, constrained to generation and joined to authoritative document/ACL
state. Raw relevance remains `LEXICAL` provenance only. Query syntax is data, never SQL boolean-mode
syntax supplied by the student.

### Dense

The application preprocessing/embedding gateway creates an explicit vector under the generation's
`EmbeddingSpace`. The adapter queries Chroma REST v2 with collection UUID and generation/scope/corpus
metadata filters. It requests more than `candidateK` within a bounded oversampling factor so denied
or stale hits can be dropped. MySQL then validates generation membership, content hash, current
lifecycle, scope, and ACL, and supplies canonical content. Distance is converted to an explicitly
named branch score only if the collection metric makes that conversion defined; raw distance is also
retained in diagnostics. No score is compared with lexical relevance.

## Fusion and final ordering (normative)

Authorize first, then assign branch ranks starting from one. Deduplicate by exact
`(boundGenerationId, chunkId)`. For each branch's post-authorization list, compute:

```text
RRF(c) = Σ over branches b containing c of 1 / (60 + rank_b(c))
```

Missing branches contribute zero. The constant is contract version `rrf-v1-k60`. A candidate present
in both branches carries both raw scores and ranks. Branch-local order is raw score descending, then
`chunkId` ascending using unsigned UTF-8 byte lexicographic order. Sort fused candidates by:

1. RRF score descending;
2. best (smallest) branch rank ascending;
3. number of contributing branches descending; and
4. `chunkId` ascending using unsigned UTF-8 byte lexicographic order.

Locale, database collation, Java default collation, hash-map iteration, branch input permutation,
thread completion, and backend arrival order are forbidden ranking inputs. RRF is represented for
comparison as an exact reduced rational (`BigInteger` numerator/denominator); sums and comparisons
use exact integer arithmetic. A scale-18, `HALF_EVEN` decimal may be serialized for diagnostics but
never controls order. The branch-count criterion is retained even where the current two-branch/k=60
formula makes it mathematically redundant, because it is explicit provenance and preserves a total
contract if a future version changes branch composition; that change still requires a new version.

This is the sole normative RRF definition. Other P12 documents reference it rather than defining
variants. Apply topK only after applicable authorization, fusion, and optional reranking. Neighbor
expansion is not implicit; if introduced, neighbors are separately authorized evidence with their
own chunk/citation IDs and cannot displace ranked hits without a versioned ranking rule.

## Optional reranker

`EvidenceReranker` accepts only the first server-bounded N fused candidates and returns a permutation
plus separate `rerankerScore`/model provenance. It cannot introduce content or IDs. Default is OFF.

- Deterministic/local rerankers declare artifact hash and version.
- A model reranker must go through existing Model Gateway, have explicit budget ownership, and never
  call legacy `AiClient` directly.
- Proposed initial deadline is 400 ms within the 1,500 ms overall deadline.
- Timeout, provider failure, malformed output, missing candidate, duplicate ID, or unauthorized ID
  discards the entire reranker output and preserves exact RRF order.
- Reranking cannot convert `EMPTY`/`UNAVAILABLE` into evidence.

## Typed result and evidence

```java
record RetrievalResult(
    String requestId,
    RetrievalStatus status,
    String contractVersion,
    String queryHash,
    String indexGenerationId,
    String embeddingSpaceId,
    List<RetrievalEvidence> evidence,
    List<RetrievalFailure> failures,
    RetrievalTiming timing
) {}

record RetrievalEvidence(
    String documentId,
    String documentVersionId,
    String chunkId,
    String citationId,
    String scopeId,
    String corpusId,
    String title,
    String sourceType,
    String sourceReference,
    String content,
    String contentHash,
    List<BranchRank> branches,
    BigDecimal fusionScore,
    BigDecimal rerankerScore,
    Integer finalRank,
    String indexGenerationId,
    String embeddingSpaceId,
    String chunkerVersion
) {}

record BranchRank(
    RetrievalBranch branch,
    int rank,
    BigDecimal rawScore,
    BigDecimal rawDistance
) {}
```

`RetrievalStatus` is `COMPLETE`, `DEGRADED`, `EMPTY`, `UNAVAILABLE`, or `DENIED_UNVERIFIED`.
`RetrievalFailure` exposes backend, phase, stable reason code, retryability, and timeout flag; it never
contains raw provider bodies or document/query content. `queryHash` is a keyed audit hash or run-local
hash, not a globally reusable hash of sensitive text.

Raw branch values, fusion score, and reranker score are distinct fields. JSON canonicalization keeps
evidence in final rank order and branches in fixed `LEXICAL`, `DENSE` order. A citation resolves only
after current authorization; it returns document/version/chunk provenance and current lifecycle, not
an unconditional content download.

## Time and partial-failure semantics

Initial configuration to validate in P12.6 (not measured facts):

- overall retrieval deadline: 1,500 ms;
- lexical branch: 600 ms;
- query embedding plus dense branch: 600 ms total;
- optional reranker: 400 ms; and
- work stops when the earlier of request cancellation or overall deadline occurs.

Branches start concurrently after the generation/principal bind. If one branch succeeds and the
other fails/times out, fuse the successful authorized list and return `DEGRADED`. If both complete
with zero candidates, return `EMPTY`. If neither produces a trustworthy result, return `UNAVAILABLE`.
An ACL/control-plane error returns `DENIED_UNVERIFIED` with zero evidence; it is never downgraded into
a partial authorization decision.

A capability mismatch is evaluated before branch start. HYBRID_REQUIRED returns UNAVAILABLE with no
evidence. Trusted ALLOW_LEXICAL_DEGRADE runs only lexical retrieval and returns DEGRADED. An ACTIVE
state never substitutes for the required capability.

Callers must not retry inside the same request beyond the adapter's explicitly bounded transport
policy. Build/index retries are separate from retrieval latency.

## Missing, stale, revoked, conflicting, and insufficient evidence

- **Missing/no answer:** return `EMPTY`; answer layer abstains from institution-specific factual
  claims and may provide only trusted generic guidance.
- **Insufficient:** retrieval may return evidence, but the answer policy must cite it and state the
  limitation; retrieval itself does not manufacture a confidence claim.
- **Stale generation:** new calls cannot select a non-active generation except a Recovery-bound tool
  attempt. Historical evidence keeps its generation/version label.
- **Revoked/retired:** exclude during final authorization. Citation resolution reports unavailable or
  revoked without revealing protected content.
- **Conflicting versions:** a generation manifest includes only the selected immutable version per
  logical document. If different documents conflict, preserve both evidence items and provenance;
  do not silently pick the highest score as truth.
- **Index/hash mismatch:** discard the candidate, record `INDEX_INCONSISTENT`, and degrade/fail based
  on remaining trustworthy branches. Never return mismatched content.

## Trust and prompt-injection boundary

All `title`, `sourceReference`, and `content` fields are untrusted data. Pre-admission mode serializes
the exact canonical `RetrievalResult` through `UntrustedEvidenceContextSource` with kind
`knowledge-retrieval-v1`; it never concatenates evidence into `ChatBusinessContextSource` or any
SYSTEM message. Runtime-tool output remains ordinary tool data governed by existing Runtime rules.

Prompt text inside a document cannot change scope, request more documents, approve a tool, alter risk
classification, or set model/system policy. Enforcement occurs in server code/SQL and is verified by
negative tests; instructions to the model are defense in depth, not the ACL.

## Pre-admission vs Runtime-tool choice

| Situation | Mode | Reason |
| --- | --- | --- |
| Trusted chat routing requires one lookup before answer | Pre-admission | Evidence can be deterministically frozen into initial context. |
| Model may need follow-up or different query | Runtime tool | Existing policy, budget, durable result, and Recovery govern the call. |
| HIGH-risk interaction | Safety transaction first; optional bounded pre-admission lookup only afterward | Alert/report cannot depend on knowledge backend. |
| Recovery of admitted run | Neither fresh lookup nor current generation | Resume exact snapshot/checkpoint; materialize durable tool result or use bound-generation retry rules. |

The trusted application chooses one primary mode per fresh request. A tool remains available only when
the execution policy deliberately allows additional retrieval; it is not an automatic duplicate of
the pre-admission lookup.

## Recovery invariants

1. Initial pre-admission evidence is part of the immutable context snapshot and is never refreshed.
2. A successful durable `knowledge_search` ToolResult/`ReliableToolOutcome` is materialized, not
   executed again.
3. A permitted unknown retry preserves run ID, toolCallId, request identity, principal-policy binding,
   and generation ID.
4. The bound generation must be retained until the maximum tool-recovery window expires.
5. If binding/authorization cannot be proven, fail closed; do not query the current generation.
6. Retrieval adds no new persistence truth that competes with P10 checkpoints/tool outcomes.

## Observability

Allowed metrics: status/reason, branch, generation/embedding-space IDs, candidate/result counts,
latency histograms, timeout flags, ACL-filter count, consistency mismatch count, and feature-flag
cohort. Default logs exclude queries, content, titles, source URLs, embeddings, provider response
bodies, and user roles/IDs. Audit access to sensitive troubleshooting data is separate, time-bounded,
and not specified by P12.
