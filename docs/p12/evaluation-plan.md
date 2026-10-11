# P12 Retrieval Evaluation Plan

## Purpose and separation from P9

P9's `EvalRunner`, baseline provenance, canonical reports, and comparator compatibility provide useful
patterns. ADR-015 and `docs/eval/README.md` correctly define `contractPass` as Runtime contract
behavior, not semantic answer quality. P12 therefore creates a separate versioned
`RetrievalEvalSuite`; it must not overload or reinterpret P9 records, baselines, or pass flags.

This plan defines measurements and **proposed engineering gates**. The audited baseline has no P12
benchmark results, so no improvement, latency, or quality number below is an observed result.

## Dataset contract

Each dataset release contains:

- `datasetId`, semantic version, canonical manifest hash, license/authorization statement, authorship
  and review date;
- exact document fixtures with stable logical keys, immutable versions, visibility/ACL grants,
  lifecycle state, language, and expected citations;
- query cases with query ID, text, trusted principal fixture, allowed corpora, relevance judgments,
  acceptable evidence sets, answerability class, and tags; and
- the chunker, lexical configuration, embedding-space fixture, generation manifest, and expected
  index hash used for the run.

Fixtures are synthetic or explicitly authorized and contain no real student conversation, health
record, student identifier, or production credential. Campus names, phone numbers, people, dates, and
URLs are fictional and visibly labeled in source metadata.

### Required case families

1. Exact keyword and identifier queries.
2. Semantic paraphrases with low token overlap.
3. Simplified-Chinese queries and mixed Chinese/English names.
4. Fictional campus mental-health services: hours, eligibility, location, escalation, and boundaries.
5. No-answer and out-of-corpus questions.
6. Conflicting sources with explicit authority/version judgments.
7. Document update: old version replaced in a new generation; old citation must not be returned.
8. Retired/revoked document and cleanup-lag cases.
9. User-, role-, corpus-, and scope-isolation negatives, including a highly relevant unauthorized
   document that must never appear.
10. Prompt-injection text embedded in title/content, asking the model or retriever to ignore policy,
    disclose other users, or change filters.
11. Lexical failure, embedding failure, Chroma timeout/version mismatch, ACL-store failure, and index
    hash inconsistency.
12. Deterministic tie cases, duplicate lexical/dense hits, and two-generation activation races.
13. Recovery fixtures: frozen pre-admission evidence, durable tool result, unknown retry against a
    retained bound generation, and missing bound generation.
14. HIGH-risk cases where retrieval hangs/fails after durable report/notification intent creation.

At least 20% of answerable queries are Chinese, at least 20% are semantic-paraphrase cases, and at
least 20% of all cases are negative/no-answer/isolation cases. These are proposed composition gates,
not claims about an existing dataset.

## Systems compared

All comparable runs use the exact same dataset, chunk manifest, ACL principal, active generation,
topK/candidateK, and query order:

- lexical-only (MySQL ngram FULLTEXT);
- dense-only (the declared embedding space and Chroma adapter);
- hybrid RRF (`rrf-v1-k60`); and
- hybrid plus the named reranker, only if introduced.

The report does not require hybrid to win every metric, and it never merges provider-dependent and
deterministic/offline scores into one baseline. An experiment is rejected as non-comparable if its
manifest, embedding space, generation, ACL policy, or retrieval contract differs without an explicit
new baseline.

## Metrics

For answerable queries, relevance judgments are chunk-level with acceptable citation aliases at the
document-version level:

- `Recall@K`: fraction of judged relevant chunks/documents present in the top K.
- `MRR@K`: reciprocal rank of the first relevant result, zero when absent.
- `nDCG@K`: graded relevance with a frozen gain/discount definition in the dataset manifest.
- Citation/source correctness: every returned citation resolves to the exact authorized document,
  version, chunk, content hash, and generation claimed by evidence.
- Version correctness: no superseded version appears in a newly activated-generation query.
- ACL/revocation leakage: count of returned or content-revealing unauthorized/revoked candidates.

For answerability and resilience:

- no-answer precision: fraction of `EMPTY`/abstain decisions that are truly unanswerable;
- no-answer recall: fraction of unanswerable cases that do not return asserted evidence;
- evidence false-positive rate on no-answer cases;
- backend-failure contract accuracy by injected fault and expected status/reason;
- index consistency accuracy: expected/actual manifest and mismatch detection;
- deterministic repeatability: byte-identical canonical results over repeated runs;
- latency p50/p95/p99 by mode and branch, excluding one-time container/image startup and separately
  reporting embedding-provider time; and
- HIGH-risk ordering correctness: durable safety facts precede retrieval invocation in every trace.

Reranker reports additionally include permutation validity, fallback rate, incremental latency, and
quality delta. A failed reranker run cannot be scored as successful hybrid-plus-reranker output.

## Proposed acceptance gates

These gates are initial review targets to confirm or revise with the first authorized baseline. They
are not current measurements.

| Dimension | Proposed P12.6 gate |
| --- | --- |
| Overall Recall@10 | >= 0.90 on answerable cases |
| Chinese Recall@10 | >= 0.85 |
| Overall MRR@10 | >= 0.75 |
| Overall nDCG@10 | >= 0.80 |
| Citation/source/version/hash correctness | 100% |
| Cross-user/scope/corpus ACL leakage | 0 cases / 0 content bytes |
| Revoked/retired leakage on new retrieval | 0 cases / 0 content bytes |
| Prompt-injection authority violation | 0 cases |
| No-answer precision and recall | each >= 0.95 |
| Injected backend/ACL failure status correctness | 100% |
| Generation exact-set and embedding compatibility checks | 100% |
| Deterministic repeatability | 100% byte-identical canonical results for deterministic profile |
| HIGH-risk report/outbox-before-retrieval ordering | 100% |
| Hybrid local-fixture latency | p95 <= 300 ms, measured after warmup on declared CI class |
| Overall configured deadline | 100% complete/cancel by 1,500 ms plus 100 ms harness tolerance |

The latency gate excludes network provider embedding. Provider profiles report p50/p95 without using
them to pass default CI. If first-baseline evidence shows the 300 ms target is unrealistic, changing
it requires an architecture-review record, not silent baseline replacement.

Hybrid must meet all security/consistency gates and must not regress lexical-only Recall@10 or
nDCG@10 by more than 0.02 absolute. A reranker is accepted only if it improves MRR@10 or nDCG@10 by
at least 0.02 absolute without violating any other gate and keeps its latency budget. These are
proposed rollout criteria, not invented results.

## Offline deterministic profile

Default CI performs no OpenAI-compatible/provider call and needs no real student data:

- fixed, versioned embedding vectors or a deterministic fixture embedding adapter;
- fake/in-process vector adapter for fast contract/property tests;
- H2 only for portable repository tests, never as proof of FULLTEXT semantics;
- MySQL 8 Testcontainers for vendor migration, ngram, ACL join, tie-order, CAS, and concurrency tests;
- Chroma 1.5.9 pinned-image integration job in CI where container support exists, with its digest
  recorded; adapter unit tests remain deterministic when Docker is absent; and
- fixed clocks, seeded fault schedules, stable IDs, canonical JSON, and no wall-clock ranking input.

The required merge gate must include MySQL-specific tests. If the host lacks containers, those tests
report `NOT_RUN` and the overall P12 gate cannot be signed from that host; a configured CI runner must
provide the evidence. Tests must not be marked green by silently skipping required infrastructure.

## Provider-dependent profile

An explicit `retrieval-provider-baseline` profile may exercise production embeddings and a model
reranker. It requires credentials outside reports, records provider/model/revision/dimension and
pricing/config snapshot, redacts request/document content, and writes a separate baseline ID. It is
manual or scheduled, never a default pull-request requirement. Rate limits, provider drift, and cost
are reported rather than hidden by retries.

## Test layers

### Unit and property tests

- identity/canonicalization golden vectors;
- chunk determinism and boundary/offset integrity;
- RRF formula, deduplication, total ordering, and permutation properties;
- deadline allocation and partial-failure state table;
- evidence canonical JSON and citation checksum;
- client input cannot set trusted fields; and
- log/telemetry redaction.

### Database and adapter integration

- V13/V14 migration from every supported V12 baseline on fresh and populated MySQL;
- `WITH PARSER ngram`, Chinese retrieval, transactional visibility, ACL joins, and `ngram_token_size`
  readiness rejection;
- generation exact-set verification and concurrent activation CAS;
- Chroma v2 version handshake, collection UUID lookup, explicit vector dimension, generation filters,
  deterministic IDs, timeout/error mapping, and stale-point post-authorization; and
- crash injection at every job/publication point listed in `data-model.md`.

### Runtime/integration

- pre-admission result is encoded only as `chat-untrusted-evidence@1` and snapshot hash is stable;
- tool input cannot select ACL/generation and contextual server identity is enforced;
- tool result/reliable outcome Recovery does not retrieve again;
- unknown retry binds the original generation and fails closed when unavailable;
- same run ID, no second admission/RUN_STARTED, and unchanged context/config snapshots;
- HIGH-risk durable report/outbox commit occurs before retrieval and survives retrieval failure; and
- external SSE and chat-history projection remain unchanged.

## Baseline and report format

Each canonical report includes dataset/manifest hash, Git commit, adapter versions, MySQL/Chroma
versions, embedding space, generation/config hashes, seed, machine class, warmup/sample counts,
per-case ranks/citations/status, aggregate metrics, failures, and whether every required profile ran.
Sensitive text is referenced by fixture ID/hash, not copied into general logs.

Baseline comparison refuses incompatible dataset, contract, generation, chunker, embedding, ACL,
topK/candidateK, or metric-definition identities. Human-readable summaries may compare them as
different experiments but cannot issue a regression PASS.

## Review evidence required for P12.6 sign-off

1. Dataset authorization and manifest review.
2. Offline canonical reports for all four systems that exist at that point.
3. MySQL and Chroma integration logs with no required skips.
4. Security negative-case report with zero leakage.
5. Recovery and HIGH-risk ordering traces.
6. Latency methodology/machine declaration and raw per-case timings.
7. Rollback drill from new engine to legacy and generation rollback drill.
8. A list of opt-in provider results clearly separated from merge-gating results.
