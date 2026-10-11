# P12 Knowledge Data Model

## Status and ownership

This is a proposed P12.1 contract. No schema described here exists at the audited baseline unless
explicitly labeled legacy. MySQL is authoritative. Chroma and the lexical projection are derived and
rebuildable.

The existing `KnowledgeChunk`/`knowledge_chunks` shape is legacy: Hibernate currently owns it and no
V1-V12 Flyway migration creates it. P12 must not retrofit production guarantees by altering that
unknown, environment-dependent table. New tables use the `kb_` prefix and are created only by new
versioned migrations.

## Canonical identity

### Canonical text and hashes

1. An extractor emits Unicode text normalized to NFC with CRLF/CR converted to LF. It does not trim
   meaningful leading/trailing content. The extractor records media type, extractor name/version,
   original byte hash, canonical text byte length, and warnings.
2. `content_hash = sha256(canonical UTF-8 text bytes)`.
3. Metadata used for identity is a schema-defined allow-list, canonicalized as JSON. Timestamps,
   job IDs, file paths, and uploader display names are excluded.
4. Canonical JSON is UTF-8, keys sorted lexicographically, arrays in semantic order, no insignificant
   whitespace, explicit null omission per schema, and decimal values in plain notation.

### Identifiers

| ID | Rule | Property |
| --- | --- | --- |
| `document_id` | UUIDv7 generated once for `(scope_id, corpus_id, source_system, logical_source_key)` | Stable across updates and title/URL changes. |
| `document_version_id` | `dv1-` + SHA-256 of canonical `{documentId, contentHash, metadataHash, extractorPolicyVersion, sourceRevision}` | Deterministic, immutable, idempotent reingestion. |
| `chunk_id` | `kc1-` + SHA-256 of canonical `{documentVersionId, chunkerId, ordinal, startOffset, endOffset, chunkContentHash}` | Deterministic and bound to exact version/chunker output. |
| `generation_id` | UUIDv7 allocated when a complete corpus build is planned | Identifies one immutable lexical+dense view. |
| `embedding_space_id` | `es1-` + SHA-256 of canonical embedding-space descriptor | Prevents model/dimension/preprocessing mixing. |
| `citation_id` | Base64url of canonical `{v:1, documentId, documentVersionId, chunkId}` plus checksum | Opaque, stable, no mutable source data. |
| `ingestion_request_id` | Caller idempotency key scoped to source connector, with a payload hash | Same key/different payload is a conflict. |

All string IDs use ASCII, binary/case-sensitive collation, and explicit maximum lengths. Hash bytes
may be stored as `BINARY(32)` with canonical string projection at API boundaries.

## Authoritative MySQL model

The exact SQL types and index names are an implementation detail of P12.2/P12.3, but the following
entities and constraints are normative.

### `kb_corpora`

- `corpus_id`, `scope_id`, `name`, `lifecycle_status`, timestamps.
- Unique `(scope_id, name)`.
- The current deployment seeds `scope_id = campus-default`; there is no implicit global corpus.

### `kb_documents`

- `document_id`, `scope_id`, `corpus_id`, `source_system`, `logical_source_key`.
- `title`, `source_uri` (sanitized), `visibility`, `lifecycle_status`, `revoked_at`, `revocation_reason`.
- `current_version_id` is a convenience pointer, not index publication truth.
- Unique `(scope_id, corpus_id, source_system, logical_source_key)`.
- Lifecycle: `ACTIVE -> RETIRED` or `ACTIVE -> REVOKED`; reactivation requires an audited new
  transition/version. Hard delete is not part of normal API behavior.

### `kb_document_acl`

- `(document_id, subject_type, subject_id, permission)` primary key.
- `subject_type` is `SCOPE`, `ROLE`, or `USER`; only `READ` is defined in P12.
- A `SCOPE`-visible document still requires the principal's trusted allowed scope/corpus.
- ACL changes increment `acl_revision` on the document and take effect in current MySQL checks
  immediately. Absence of an applicable grant means deny.

### `kb_document_versions`

- `document_version_id`, `document_id`, `content_hash`, `metadata_hash`, `source_revision`.
- Extractor name/version/policy, media type, language hint, canonical byte/character counts.
- Immutable title/source metadata snapshot needed for citations.
- State: `STAGED`, `INDEXABLE`, `FAILED`, `RETIRED`; generation membership determines published
  visibility. State transitions never update identity-bearing fields.
- Unique identity hash and foreign key to `kb_documents`.

### `kb_chunks`

- `chunk_id`, `document_version_id`, ordinal, start/end character offsets, `content_hash`, content.
- Chunker name/version/config hash, language, and optional heading/page provenance.
- Unique `(document_version_id, ordinal)` and unique deterministic `chunk_id`.
- Content and all identity/provenance fields are append-only.

### `kb_ingestion_jobs`

- Job/request/source identity, payload hash, document/version result IDs, state, reason code.
- Durable lease owner, unique `claim_token`, lease expiry, started-attempt count, next-attempt time.
- State: `PENDING`, `CLAIMED`, `EXTRACTING`, `CHUNKED`, `COMPLETED`, `FAILED_RETRYABLE`,
  `FAILED_PERMANENT`, `UNCERTAIN`.
- Every claim-owned transition matches job ID, expected state, owner, and claim token, following the
  fencing lesson in ADR-026. External work is not performed while holding a database transaction.

### `kb_embedding_spaces`

- `embedding_space_id`, provider type, model name, immutable model revision when available,
  dimension, normalization, distance metric, preprocessing version, contract version.
- Descriptor columns are immutable and unique together.
- Secrets, API keys, base-URL credentials, and raw provider responses are excluded.

### `kb_index_generations`

- `generation_id`, corpus/scope, embedding space, chunker identity, lexical config hash.
- Manifest hash, expected version/chunk counts, actual lexical/vector counts, verification report hash.
- State: `BUILDING`, `VERIFYING`, `READY`, `ACTIVE`, `RETIRED`, `FAILED`.
- `ACTIVE` is a projection of the pointer; code must not select `MAX(created_at)`.
- Failure reason and timestamps are auditable. Identity/configuration fields are immutable.

### `kb_generation_members`

- Primary key `(generation_id, chunk_id)` with document/version IDs and expected content hash.
- This is the authoritative manifest for exact-set verification and idempotent repair.
- A generation contains a complete corpus view, not only the delta from its predecessor.

### `kb_active_generations`

- One row per corpus: `corpus_id`, `generation_id`, `previous_generation_id`, `pointer_version`,
  activation actor/time.
- Activation executes `UPDATE ... WHERE corpus_id=? AND pointer_version=? AND generation_id=?old` in
  the same transaction that records the activation audit event.
- Exactly one pointer exists for an initialized corpus. A READY generation may be reactivated for
  rollback if retained and compatible.

### `kb_index_tasks`

- `(task_id, generation_id, backend, partition_key)` and deterministic task payload hash.
- Backend is `LEXICAL`, `EMBEDDING`, `VECTOR`, `VERIFY`, or `CLEANUP`.
- Uses the same fenced lease and durable attempt fields as ingestion jobs.
- Unique logical task key prevents duplicate work. Retry is idempotent; uncertain external writes are
  checked by deterministic IDs before retry.

### `kb_lexical_projection`

- Primary key `(generation_id, chunk_id)`; contains scope/corpus/document/version IDs, content,
  content hash, and minimal citation fields.
- MySQL variant has `FULLTEXT(content) WITH PARSER ngram` and supporting generation/filter indexes.
- Search joins authoritative current document lifecycle and ACL tables. Projection metadata alone
  never grants access.
- H2 variant exists for structural tests only and is not a production retrieval implementation.

### `kb_generation_audit`

- Append-only activation, rollback, verification, retirement, and cleanup facts.
- Records identities, actor/reason, old/new pointer, configuration hashes, and counts, never document
  body or query text.

## Derived Chroma model

Collection identity is the immutable `embedding_space_id`; deployment records the Chroma collection
UUID returned by the verified REST v2 control plane. Each point contains:

- ID: `{generation_id}:{chunk_id}`.
- Explicit vector with exactly the embedding-space dimension.
- Document text only if required for retrieval output validation; MySQL remains the return authority.
- Metadata: generation, scope, corpus, document, version, chunk, content hash, lifecycle projection.

The dense adapter queries by explicit query embedding and filters at least generation/scope/corpus.
It then fetches authoritative chunk/body and ACL state from MySQL. A point not present in the exact
generation manifest, with a hash mismatch, or denied by current policy is discarded and reported as
an index-consistency defect.

## Version, update, and retirement semantics

- Same logical key and identical identity payload: reuse the version; no new indexing side effect.
- Same logical key and different identity payload: append a version; never mutate the prior version.
- Changed chunker or embedding space: build a new generation, even with unchanged documents.
- Incremental builder may copy/reference unchanged manifest members and skip already verified vector
  upserts, but the resulting generation manifest is complete.
- Source retirement/revocation immediately affects MySQL authorization and schedules a new
  generation plus derived cleanup.
- Physical deletion waits for audit retention, active/rollback generation retention, and any legal
  hold. P12 does not promise erasure of immutable Runtime snapshots; that is a separate retention
  policy concern.

## Generation state machine

```text
BUILDING -> VERIFYING -> READY -> ACTIVE -> RETIRED -> cleanup eligible
    |           |          |        `----> READY (rollback target retained)
    `-----------+----------+---------------------------> FAILED
```

Only `READY` may be activated. A generation cannot be edited after `VERIFYING` begins. Activation of
one generation demotes the old active generation to retained `READY`/`RETIRED` according to policy in
the same transaction. The pointer, rather than a mutable state scan, defines reader truth.

## Crash and retry matrix

| Crash point | Durable observation | Recovery action |
| --- | --- | --- |
| Before catalog transaction commits | No version/job | Safe caller retry with same idempotency key. |
| After version/job commit, before worker | `PENDING` job | Scanner claims with new token. |
| During extraction/chunk transaction | Prior state or complete append | Reread; deterministic hashes prevent duplicates. |
| After Chroma upsert, before task completion | Point may exist | Read/check deterministic point ID and hash, then complete or upsert idempotently. |
| During lexical batch | Partial generation rows | Repeat upsert/delete only within non-active generation, then exact-set verify. |
| After verification, before pointer swap | READY generation, old active pointer | Retry CAS activation. |
| After pointer swap, before response | New pointer durable | Reread pointer; do not activate twice. |
| During cleanup | Some derived rows removed | Cleanup is idempotent and never targets active/retained generations. |

## Migration strategy

- **P12.2 / V13:** common Flyway schema for corpus, document, ACL, version, chunk, ingestion job,
  embedding space, generation, manifest, active pointer, task, and audit tables. Seed only explicit
  configuration-owned corpora; do not copy legacy rows automatically.
- **P12.3 / V14:** configure `classpath:db/migration,classpath:db/migration/{vendor}`. MySQL V14 adds
  `kb_lexical_projection` and ngram FULLTEXT index; H2 V14 adds a structural table without claiming
  ranking equivalence. A MySQL startup compatibility check validates engine/parser/token size.
- **P12.4 / V15:** add only adapter state actually needed after implementation review, such as verified
  collection UUID and publication health. Do not store credentials. If V13 already covers it, V15 is
  explicitly unnecessary rather than an empty migration.
- V1-V12 remain byte-for-byte unchanged. Legacy data import is an explicit, idempotent admin job with
  dry-run/reporting and is not part of startup migration.

## Database invariants to test

1. Same ingestion request/payload produces one immutable version and one logical job result.
2. Same request key with another payload is rejected.
3. Version/chunk identity fields cannot be updated.
4. Every generation member resolves to an exact chunk/version/document chain.
5. Only a verified READY generation can win the active-pointer CAS.
6. Concurrent activations have exactly one winner.
7. Revoked/retired/unauthorized documents fail the authoritative join even if derived rows remain.
8. A stale claim token cannot advance an ingestion/index job.
9. Dimension/embedding-space mismatch cannot reach READY.
10. Cleanup cannot select the active generation or a retained rollback generation.
