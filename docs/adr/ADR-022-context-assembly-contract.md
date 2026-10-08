# ADR-022: Context Assembly Contract

- Status: Accepted
- Date: 2026-10-08
- Phase: P11.1

## Context

Fresh Agent execution previously accepted `AgentRunSpec.messages` as an opaque caller-built list.
That preserved Runtime behavior but did not define one deterministic contract for composing all
model-visible initial context, recording its sources, or proving which initial context a durable Run
used. `AgentRuntimeContext` already owns cancellation, lease, streaming, middleware, and other
execution-scoped state; using its attributes for prompts, conversation history, skills, or retrieved
knowledge would merge unrelated runtime and semantic concepts.

P11.1 establishes only the assembly and provenance foundation. Conversation memory, skills, and
knowledge retrieval remain deferred to their own phases.

## Decision

Semantic context and runtime context are separate concepts. `ContextSource` is a framework-neutral,
read-only provider. It receives immutable run, session, user, and request-message input and returns
an immutable `ContextContribution`. Source failures fail closed and sources do not write durable
state, call the model, execute tools, or mutate the request.

`AgentContextAssembler` validates source metadata, rejects duplicate `sourceId + sourceVersion`
identities, and sorts sources by explicit `order` and then `sourceId`. It loads each contribution
once, records source/version/order, message count, and an exact content hash, then flattens messages
in that deterministic order. Spring registration order, map iteration order, current time, and random
identifiers do not influence semantic ordering or identity.

`RequestMessageContextSource` is the only P11.1 source. It contributes the existing
`AgentRunSpec.messages` unchanged, so P11.1 changes provenance and execution guarding without
changing model-visible behavior.

The immutable `AgentContextSnapshot` identity is:

```text
contextHash = SHA-256(canonical semantic JSON)
snapshotId = context-v1-{contextHash}
```

Canonical semantic JSON covers schema version, run/session/user identity, ordered source identity,
version and order, contribution content hashes, and every exact final `AgentMessage`, including tool
calls and canonicalized arguments. `createdAt` is durable metadata but is excluded from the hash.

`agent_context_snapshots` is append-only and content-addressed. Same identity and same semantic
content is idempotent; same identity and different content fails hard. `agent_runs.context_snapshot_id`
is a nullable foreign key so pre-P11.1 history remains readable and is not backfilled.

Fresh execution order is fixed as:

```text
assemble context
→ persist AgentContextSnapshot
→ attach contextSnapshotId and assembled messages
→ persist P9.3 execution configuration snapshot
→ P7 coordination
→ P6 admission
→ Agent Runtime
```

Recovery does not enter the context assembly coordinator. `RecoveryCheckpoint.messages` remains the
continuation authority, and P10 never rebuilds historic context from current sources. The context
snapshot records initial-context provenance; it does not replace a recovery checkpoint.

## Consequences

- All future model-visible initial context must integrate through the same deterministic assembler.
- Conversation memory, skills, and RAG sources may be added later without using Runtime attributes.
- Context assembly or persistence failure creates no AgentRun and performs no Runtime/model/tool work.
- Historic Runs retain their original nullable linkage and recovery semantics remain unchanged.
- P11.2 Conversation Memory, P11.3 Skills, P11.4 chat migration, and P12 RAG are outside this ADR.
