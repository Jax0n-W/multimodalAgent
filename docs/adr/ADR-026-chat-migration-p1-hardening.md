# ADR-026: Chat Migration P1 Hardening

## Status

Accepted for P11.4 hardening.

## Context

ADR-025 moved final-answer generation to the unified runtime, but two P1 gaps remained:

1. retrieved and multimodal text was concatenated into a trusted `SYSTEM` message; and
2. high-risk notification delivery was an in-memory background action with no durable intent.

PII sanitization does not establish trust. SMTP, HTTP, and MCP delivery also do not expose a
provider idempotency contract, so the runtime cannot honestly promise exactly-once delivery.

## Decision

### Trusted context boundary

Application-owned routing and safety policy remains a plain `SYSTEM` contribution from
`chat-business-context@1`. RAG and multimodal evidence is serialized by
`chat-untrusted-evidence@1` as a canonical JSON data envelope prefixed with
`UNTRUSTED_EVIDENCE_DATA_V1`. It uses the existing non-system, non-student `ASSISTANT` role so
there remains exactly one current student `USER` message. Snapshot provenance records both
sources independently.

The envelope is a structural authority boundary, not a claim that prompt injection is solved.
The trusted policy explicitly tells the model to treat every envelope field as data rather than
instructions. PII sanitization remains defense in depth only.

### Durable high-risk notification intent

V11 adds `high_risk_notification_intents`. A high-risk report, its compatibility
`AlertRecord` rows, and one intent per normalized recipient are committed atomically. A missing
recipient configuration creates a durable `PERMANENT_FAILURE` intent instead of appearing
successful. Dispatch is kicked only after commit; startup and scheduled scans recover missed
kicks.

Workers claim due intents with a conditional database update into `CLAIMED`. A successful claim
increments the durable attempt count. Before invoking the external notifier, the worker durably
transitions to `PROCESSING`. An expired `CLAIMED` lease is safe to retry; an expired `PROCESSING`
lease is ambiguous and becomes `UNCERTAIN`. Confirmed pre-send failures may transition to
`FAILED_RETRYABLE` up to the configured limit. Ordinary transport failures or success followed
by a persistence failure transition (or are conservatively quarantined) to `UNCERTAIN`. They are
not automatically redelivered because an external side effect may already have happened.

V11 intentionally has no foreign keys to `psychological_reports` or `alert_records`: those legacy
tables are still Hibernate-managed and do not exist when Flyway migrates a fresh database. The
application transaction is the integrity boundary until those tables are migrated to Flyway.

## Consequences

- External evidence cannot acquire `SYSTEM` authority through the migrated chat path.
- Context snapshots preserve deterministic ordering and distinct provenance.
- High-risk delivery intent survives process restart and concurrent scanners cannot both claim
  the same pending intent.
- Delivery semantics are explicitly at-most-once after an ambiguous handoff, with operator-visible
  `UNCERTAIN` state; no exactly-once claim is made.
- This change does not alter Agent Recovery, add a workflow engine, or enter P11.5/P12.
