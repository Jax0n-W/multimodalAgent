# P13 Final Delivery Report

## A. Release identity

- Repository: `Jax0n-W/multimodalAgent`
- Branch: `main`
- Baseline: `db381e8ada0134d8fc0b6c72f1003d7517e1297d`
- Release commit: the commit containing this report (`chore: finalize multimodal agent delivery`)
- Demo host: Windows 11, Java Temurin 17.0.19, Maven 3.9.16, Docker Desktop 29.5.2,
  Ollama 0.35.1
- Demo model: `mindbridge-qwen2.5-7b-ft:latest`
- Demo storage: MySQL 8.4 and Redis 7.2 in Docker Compose
- Retrieval mode: requested `HYBRID_RRF`; without a configured embedding provider, live requests
  explicitly reported `LEXICAL_ONLY_DEGRADED`

The release commit intentionally excludes the user's pre-existing Modelfile, SecurityConfig and
handoff workspace changes.

## B. Working capabilities

- Unified durable Agent Runtime with budgets, streaming model gateway, governed tools and persisted
  execution history.
- Immutable configuration/context snapshots, checkpoints, same-run recovery, reliable tool outcomes
  and durable retry-attempt accounting.
- Bounded conversation memory plus deterministic, versioned Skills. Server-owned HIGH-risk decisions
  suppress ordinary Skills.
- Hybrid RAG MVP with independently testable BM25, dense and RRF modes, provenance, bounded persisted
  embeddings and truthful degradation. Retrieved material is isolated as untrusted evidence.
- HIGH-risk report, AlertRecord and notification intent are committed before optional retrieval.
  Notification delivery uses a durable Outbox with lease recovery and claim fencing.
- Student SSE chat, idempotent chat-history projection, admin reporting and administrator-only public
  knowledge ingestion.

## C. Verification

### Automated regression

- Final command: `mvn clean test`.
- Final result: 782 tests, 0 failures, 0 errors, 0 skipped (`BUILD SUCCESS`, 2 min 10 s).
- MySQL Testcontainers 8.0.36 and Redis Testcontainers 7.2 actually started during the suite.
- Flyway V1-V14 ran against a real MySQL container. V13 preserves knowledge text capacity; V14
  preserves durable snapshot/checkpoint/outcome payload capacity.
- Legacy chat and RAG rollback switches are covered by `ChatMigrationLegacyModeTest` and
  `HybridRagMvpIntegrationTest`.
- Recovery coverage includes frozen evidence reuse, same-run resume and no duplicate safety side
  effects; no artificial process crash was injected through the public HTTP API.

### Retrieval measurements

The 30-case offline suite uses synthetic/authorized fixture documents and deterministic fixture
embeddings. It exercises production BM25/dense/RRF implementation, but is not a real embedding
provider benchmark.

| Mode | Recall@5 | MRR@10 | Top-1 source accuracy | Average latency |
| --- | ---: | ---: | ---: | ---: |
| BM25-only | 0.6667 | 0.6667 | 0.6667 | 2.398 ms |
| Dense-only | 1.0000 | 1.0000 | 1.0000 | 2.901 ms |
| Hybrid RRF | 1.0000 | 1.0000 | 1.0000 | 3.415 ms |

- Real LLM + fixture embedding: PASS for sleep, academic pressure and interpersonal scenarios; each
  cited the expected fixture source.
- Real LLM + real embedding provider: **NOT_RUN** because no external embedding credential/provider
  was configured. The fixture score is not presented as a real-provider result.

### Docker and HTTP demonstration

- `docker compose config --quiet`: PASS.
- Compose started the application with healthy MySQL/Redis dependencies and the real local Ollama
  model. The application applied V13 then V14 to an existing volume and reached port 8080.
- The live schema reported `LONGTEXT` for chat/report content and durable config/context/checkpoint/tool
  payloads; four bundled knowledge chunks loaded successfully before administrator demo ingestion.
- `/api/agent/status` reported Ollama, the expected model, real-model enabled, Chroma disabled and
  `ragTopK=4`.
- HTTP SSE: ordinary CHAT and sleep, academic-pressure and interpersonal cases all ended with `done`.
- Administrator-ingested public demo documents produced live CONSULT evidence with
  `LEXICAL_ONLY_DEGRADED`; sleep selected `sleep-guidance@2`, interpersonal selected
  `interpersonal-support@1`, and evidence stayed in the untrusted Assistant envelope.
- Two-turn HTTP memory reused the same session id, restored two prior messages and correctly recalled
  that the stated exam was on Wednesday. Four chat messages were projected.
- HIGH-risk HTTP smoke created report, AlertRecord and notification intent before the context snapshot;
  the Outbox reached `DELIVERED`, ordinary Skill message count was zero, and the run completed.
- Student access to an admin conversation returned 403; an unauthenticated chat request returned 401.
- Demo logging exposed only report/alert identifiers, not the consultation text or recipient.
- Compose was stopped with volumes retained after verification.

## D. Limitations

- This is the bounded Hybrid RAG MVP described by ADR-028, not ADR-027's full production knowledge
  platform.
- Production multi-tenant knowledge ACLs, immutable Knowledge Generations, Chroma v2 publication,
  production-scale retrieval/SLO validation and online index rollout are deferred.
- A fresh demo contains only small bundled public documents; administrators must ingest approved campus
  material for topic-specific evidence.
- Without compatible persisted embeddings, Hybrid requests degrade to lexical retrieval. A real dense
  provider remains unverified in this environment.
- SMTP delivery and remote Excel integration were not exercised; the safe demo uses log notification
  and local Excel modes.
- Demo credentials and database passwords must be replaced outside local development. The system is a
  support tool, not a clinical diagnosis, treatment or safety guarantee.

## E. Resume-ready technical highlights

- Built a Java/Spring Boot durable Agent Runtime with execution budgets, model/tool event history,
  immutable snapshots and crash-safe same-run recovery.
- Designed typed Tool contracts and policy governance with idempotency, reliable outcome persistence,
  reconciliation and retry-aware budget reconstruction.
- Implemented deterministic context assembly combining bounded memory, versioned Skills and isolated
  untrusted RAG evidence without elevating retrieval content to SYSTEM authority.
- Delivered BM25, persisted dense cosine and exact reciprocal-rank fusion (`1 / (60 + rank)`) with
  deterministic ordering, provenance and explicit degradation states.
- Implemented HIGH-risk transactional safety facts and a durable notification Outbox with retry
  classification, lease recovery and ABA-resistant claim fencing.

## F. Final decision

`PROJECT MVP RELEASE: PASS`
