# ADR-023: Durable Conversation Memory Contract

- Status: Accepted
- Date: 2026-10-10
- Builds on: ADR-022 Context Assembly Contract

## Context and motivation

Fresh Agent runs need bounded continuity across multiple runs in the same authenticated user
session. The memory used by a run must be explainable, immutable after admission, isolated by
user, and compatible with the durable execution and recovery contracts.

## Durable memory source of truth

Conversation memory is derived from existing durable facts. A visible turn combines an
`agent_runs` identity and completed final result with the original request contribution in its
verified `agent_context_snapshots` row. P11.2 creates no conversation table, event stream, cache,
or second write pipeline. Redis and the legacy chat tables are not Runtime memory truth.

## Session identity

`runId` identifies one execution; `sessionId` groups executions. The Agent run API accepts an
optional `sessionId` of at most 64 characters. An omitted value falls back to `runId`, preserving
the old one-run session behavior. `userId` is accepted only from the authenticated principal and
is never client-controlled.

## Completed-only visibility

A candidate must match both authenticated `userId` and `sessionId`, must not be the current run,
and must have `status=COMPLETED`, `stopReason=COMPLETED`, `completedAt`, `finalContent`, and a
non-null context snapshot identity. This deliberately excludes the crash window in which the
completion event was projected but `finalizeRun()` has not durably stored the final answer.
Historical runs with a null context snapshot are skipped for compatibility.

## Historical turn reconstruction

The snapshot store restores and verifies canonical JSON, hash, provenance, and stored metadata.
The reader additionally checks snapshot run/session/user identity against the historical run.
It walks provenance message counts and extracts only the exact `request-messages@1`
contribution. A turn is accepted only when that contribution occurs exactly once and contains one
plain USER message. Ambiguous legacy request shapes are skipped; missing, corrupt, or
identity-conflicting non-null snapshots fail closed. The complete historical snapshot is never
replayed, preventing recursive duplication of earlier memory, prompts, tools, or retrieval data.

## Deterministic ordering and window

The durable query orders candidates by `completedAt DESC, runId DESC` and is bounded. Policy
again applies that ordering, selects the newest complete turns subject to `maxTurns` and the sum
of USER plus ASSISTANT Java character counts, then emits selected turns by
`completedAt ASC, runId ASC`. A turn that would exceed the remaining character budget ends
selection; turns are never split. Defaults are disabled, six turns, and 12,000 characters.
Invalid non-positive limits fail fast.

## ContextSource integration

`ConversationMemorySource` is a framework-neutral `ContextSource` with identity
`conversation-memory@1` and order zero. `RequestMessageContextSource` remains order zero.
ADR-022's order/source-id/source-version sort therefore places historical memory before the
current request without relying on registration order. Empty history is a valid empty
contribution and remains visible in snapshot provenance.

The dependency direction is:

```text
AgentContextAssembler
  -> ConversationMemorySource
  -> ConversationMemoryReader (port)
  <- JpaCompletedRunConversationMemoryReader (adapter)
```

The context domain has no Spring, JPA, Redis, or HTTP dependency.

## Snapshot consistency

Memory and current request messages are assembled before configuration snapshotting, lease
coordination, P6 admission, and model/tool execution. They are included in the immutable context
snapshot messages, source provenance, content hashes, canonical JSON, and content-addressed
snapshot identity. Later run completions cannot mutate an admitted run's snapshot.

## Recovery isolation

Recovery does not execute context assembly and has no dependency on conversation memory. It
continues exclusively from the durable `RecoveryCheckpoint.messages`. Changes to memory data or
window configuration after a crash therefore cannot change continuation state.

## Privacy and data ownership

Every query requires authenticated user identity and session identity, followed by defensive
identity checks on both run and snapshot. No memory-reading API is added. Implementations and
errors do not log or embed conversation content, snapshot JSON, or tool arguments. A SHA-256 hash
provides integrity identity, not confidentiality.

Production deployment still requires explicit retention and deletion policy, encryption and key
management, access auditing, incident response, and authorization review for sensitive student
counselling data. Those controls are not claimed by this ADR.

## Failure semantics

Database failures, missing referenced snapshots, corrupt canonical content, and identity
mismatches propagate as context assembly failure. No current context snapshot is written, no P6
run is admitted, and no model or tool executes. Legitimate empty history and historical null
snapshot references are skipped without fabricating content.

## Alternatives rejected

- Redis as durable truth: cache lifetime and consistency do not match durable execution facts.
- Legacy `ChatService` as Runtime memory owner: it would couple the new Runtime to the old app
  path before P11.4 migration.
- Replaying an entire historical context snapshot: recursively duplicates prior memory and may
  expose system, tool, or retrieval content.
- Recovery rebuilding memory: violates frozen checkpoint continuation semantics.
- A duplicated conversation write pipeline: introduces a second truth that can diverge from P6.
- LLM summarization in P11.2: nondeterministic, adds model calls, and belongs to later phases.

## Deferred work

P11.3 skills, P11.4 legacy chat migration, semantic/vector memory, summarization, user-profile
extraction, retention/deletion workflows, encryption controls, and any index migration justified
by production query plans are deferred. No session lock or forced serialization is introduced.
