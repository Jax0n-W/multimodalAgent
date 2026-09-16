# ADR-006: Distributed Run Coordination

- Status: Accepted and semantically hardened through P7H
- Date: 2026-09-16

## Context and scope

P6 made execution history durable, but database persistence alone does not prevent two application
instances from concurrently attempting the same `runId`. P7 introduces distributed coordination
without turning Redis into another runtime state machine.

P7.1 freezes only the coordination semantics, domain port, failure vocabulary, lease-session state
model, configuration contract, and architecture constraints. It does not connect to Redis and does
not execute a coordinated run.

## Decision

### Authority boundaries

The three state authorities remain deliberately separate:

- Runtime Core is the authority for execution truth: model, tool, iteration, and terminal facts.
- MySQL is the authority for successfully persisted durable execution history and durable run
  identity.
- Redis will be ephemeral coordination state for active execution ownership only.

Redis must not contain or decide iteration state, model state, tool state, run terminal state,
conversation history, checkpoints, recovery state, or durable history. Runtime Core must not depend
on Redis, Spring Data Redis, a coordination adapter implementation, or persistence implementation.

### Coordination scope and ownership

Coordination is scoped to `runId`, not `sessionId`. P7 guarantees at most one active lease owner for
a run ID; it does not serialize all runs in a conversation session.

Lease contention is rejection, not waiting. A second caller for an already active `runId` must fail
with `RunAlreadyActiveException`; it must not wait for the first owner and then execute the same run
again.

Lease acquisition must occur before P6 durable admission. The intended full-P7 composition is:

```text
AgentExecutionRequest
  -> CoordinatedAgentExecutionCoordinator
  -> acquire run lease
  -> PersistentAgentExecutionCoordinator
  -> durable admission
  -> AgentExecutionCoordinator
  -> Runtime
  -> durable finalization
  -> stop renewal
  -> compare-and-delete lease
```

P7.1 documented this composition. P7.3 implemented its acquire/delegate/terminal-release shell.
P7.4 adds automatic renewal and Runtime safe-boundary enforcement, while production activation
remains deferred.

### Redis and database guards are complementary

Redis protects concurrent active ownership. The database unique constraints continue to protect
durable run identity. A successful Redis acquire does not prove that a run is safe to execute as a
brand-new run: a durable row may already exist from an earlier execution whose ephemeral lease has
expired or been released. Durable admission therefore remains mandatory after lease acquisition.

A `leaseToken` is an unguessable ownership credential used by future compare-and-renew and
compare-and-delete operations. It is not a business identifier, is not a `runId`, and must never be
replaced by a Redis key. The future default key schema is:

```text
mma:coord:v1:run:{runId}:lease
```

Key construction belongs to the future Redis adapter, not the domain contract.

### Lease lifecycle and execution authority

The frozen session states are `ACTIVE`, `LOST`, `CLOSING`, and `CLOSED`. Legal transitions are:

```text
ACTIVE -> LOST
ACTIVE -> CLOSING
LOST -> CLOSING
CLOSING -> CLOSED
```

`LOST -> ACTIVE`, `CLOSED -> ACTIVE`, and `CLOSED -> LOST` are forbidden. Ownership loss is
monotonic: once ownership cannot be proven, later infrastructure recovery or a successful-looking
renewal must not restore execution authority for that execution. A new execution must acquire a new
lease and use a new session.

`EXPLICIT_LEASE_LOSS` means coordination explicitly proved the current token is no longer the
owner, such as token mismatch or missing key. `COORDINATION_UNAVAILABLE` means infrastructure could
not prove ownership because the outcome is unavailable or unknown. They are observably distinct,
but both move the session from `ACTIVE` to `LOST` and prohibit future Core work.

P7 provides cooperative execution fencing at Runtime operation boundaries only. If a model or tool
operation has already emitted `MODEL_STARTED` or `TOOL_STARTED`, it must be allowed to reach its
frozen Core terminal fact before future work is stopped. Coordination failure may prevent future
Core work but may never rewrite an already-established Core fact.

This is not external-resource fencing. A remote system called by a tool cannot be made safe from a
stale owner without that resource participating in a fencing-token or idempotency protocol.

### Failure model and Core outcomes

Coordination failures are harness/infrastructure outcomes, not Runtime Core outcomes. Therefore P7
does not add `COORDINATION_ERROR`, `REDIS_ERROR`, or `LEASE_LOST` to `AgentStopReason`.

The exception hierarchy is:

```text
ExecutionCoordinationException
  |- RunAlreadyActiveException
  |- RunLeaseLostException
  `- CoordinationUnavailableException
```

The result types of `RunLeaseStore` remain Redis-neutral and do not collapse distinct outcomes into
booleans:

- acquire: acquired, already active, or coordination unavailable;
- renew: renewed, explicit ownership loss, or coordination unavailable;
- release: released, no longer owner, or coordination unavailable.

### P6 and P7 failure precedence

If persistence and coordination both fail during the same already-started operation, the caller's
primary exception is `ExecutionPersistenceException`. This preserves the P6 caller-visible contract.
The coordination failure must remain available for diagnostics in the future, for example as a
suppressed exception or structured observation. It must not replace the persistence failure or
rewrite Core truth.

This precedence applies only after execution has entered the P6/Runtime path and both infrastructure
failures exist. If lease acquisition fails before P6 durable admission, only the corresponding
`ExecutionCoordinationException` is returned; no persistence failure exists.

P7.1 does not modify P6 to synthesize this dual-failure path. It freezes the rule for later
composition work.

### Configuration contract

The Redis adapter is configured by `enabled`, `keyPrefix`, `leaseTtl`, `renewInterval`, and
`watchdogThreads`.
Defaults are disabled, prefix `mma:coord:v1:run`, TTL 60 seconds, and renewal interval 20 seconds.
The watchdog scheduler defaults to four shared daemon threads. Both durations must be positive,
the prefix must be non-blank, the scheduler capacity must be positive, and three renewal intervals
must fit within one TTL.

Declaring these properties alone does not enable coordination. When the feature is explicitly
enabled, Spring may compose the Redis store, one shared renewal scheduler, a watchdog factory, and
the coordination boundary middleware. The application execution entry point is still not switched
to the coordinated flow.

### P7.2 Redis lease primitives

P7.2 implements the `RunLeaseStore` port with Spring Data Redis while leaving execution lifecycle
unwired. Acquire is one atomic Redis `SET key token NX PX ttl` operation. Renew uses an atomic Lua
compare-token-and-`PEXPIRE` script, and release uses an atomic Lua compare-token-and-`DEL` script.
Redis client failures map to the existing coordination-unavailable results and do not leak Redis or
Lettuce exceptions through the domain port. The adapter never changes `RunLeaseSession`; lifecycle
state remains the responsibility of later coordination wiring.

The adapter can be registered as a Spring bean only when
`multimodal-agent.coordination.redis.enabled=true`. Merely defining the primitive does not acquire a
lease or connect it to an Agent execution.

### P7.3 coordinated execution lifecycle

P7.3 composes one execution inside an ownership shell:

```text
acquire run lease
  -> create ACTIVE RunLeaseSession
  -> execute PersistentAgentExecutionCoordinator
  -> token-based release in terminal cleanup
  -> close RunLeaseSession
```

Contention and unavailable acquisition fail closed before P6 durable admission. Once acquisition
succeeds, cleanup attempts release even when P6 admission, Runtime, or persistence finalization
fails. If delegate execution and cleanup both fail, the delegate exception remains primary and the
coordination exception is suppressed. In particular, `ExecutionPersistenceException` retains the
P6 precedence frozen above.

Release failure after a successful delegate cannot rewrite the completed Core result. An
unavailable release is logged as `COORDINATION_UNAVAILABLE`; `NO_LONGER_OWNER` first moves the
session to `LOST` and records explicit lease-loss diagnostics. Both then close the local session,
without blind deletion, retry, or a new `AgentStopReason`.

The lifecycle coordinator is deliberately not registered as the production application entry.

### P7.4 watchdog renewal and safe-boundary enforcement

P7.4 starts one execution-scoped `RunLeaseWatchdog` after acquisition and before P6 admission. All
watchdogs share an injected scheduler; no execution creates its own thread. Cleanup always stops
the watchdog before token-based release, and `stop()` serializes with renewal so a stale scheduled
task cannot renew after release.

Each tick atomically renews through `RunLeaseStore`. `RENEWED` preserves `ACTIVE`.
`EXPLICIT_LEASE_LOSS`, `COORDINATION_UNAVAILABLE`, and unexpected renewal exceptions permanently
move the session to `LOST`, preserving the first failure. Infrastructure recovery cannot revive
that execution's authority.

The coordination middleware is outer to the persistence middleware:

```text
coordination pre-check
  -> persistence pre-check
    -> Core operation
  -> persistence post-check
-> coordination post-check
```

Its order is 100 and persistence order is 200. The current `RunLeaseSession` reaches the single
`AgentRuntimeContext` through a generic harness context contributor and typed `RuntimeAttributes`;
there is no `ThreadLocal`, static execution registry, second context, or Redis lookup per boundary.

If ownership is already lost, a new Model or Tool operation cannot start. If ownership is lost
during an already-started operation, that operation still emits its truthful `MODEL_COMPLETED`,
`MODEL_FAILED`, `TOOL_SUCCEEDED`, or `TOOL_FAILED` fact before the post-check stops future work.
The Core uses its frozen middleware outcome `RUN_STOPPED(INTERNAL_ERROR)`. The outer coordinator
then exposes the session's specific `RunLeaseLostException` or `CoordinationUnavailableException`
to the caller. If persistence also failed, `ExecutionPersistenceException` remains primary and the
coordination failure is retained as one suppressed diagnostic.

P7.4 is cooperative fencing at Runtime boundaries. It does not fence external systems called by a
tool, interrupt an in-flight remote request, recover a lost run, or transfer execution to a new
owner.

### P7H concurrency and lifecycle invariants

P7H freezes the following coordination invariants:

- `stop()` and renewal are linearized on one watchdog monitor. `stop()` waits for an in-flight
  renewal, and once it returns no renewal remains inside `RunLeaseStore` and no later scheduled
  callback can perform a meaningful renewal. Cleanup must preserve the order `stop -> release`.
- A delayed old-token renewal remains safe after release or re-ownership because Redis renew is an
  atomic compare-token-and-expire operation. It can neither extend nor delete a new owner's lease.
- `LOST` is irreversible for an execution. A later healthy Redis response cannot restore authority,
  and P7 never automatically reacquires a lost lease.
- `CLOSED` is terminal. Asynchronous renewal and scheduler callbacks use a no-op transition when
  cleanup has already begun; they cannot produce `CLOSED -> LOST`, revive authority, or leak a
  lifecycle exception from the callback.
- Explicit token loss and coordination unavailability remain distinct diagnostics, even though
  both fail closed and prevent future Core work.
- Scheduler availability is part of coordination availability. Closing the production scheduler
  notifies all active watchdogs and transitions their sessions to
  `LOST(COORDINATION_UNAVAILABLE)`; initial scheduling rejection has the same fail-closed outcome.
- The shared scheduler is an availability boundary because renewal uses blocking Redis calls. Two
  threads allow two slow calls to starve every other run, so the conservative default is four and
  `watchdogThreads` is configurable. This is capacity hardening, not dynamic autoscaling.
- Stop-before-release safety deliberately waits behind an in-flight Redis renewal. That wait is
  safe only when Redis operations have bounded completion. Production configuration sets both
  Lettuce command timeout and connection timeout to 2 seconds. An operation that must first connect
  and then issue a command is therefore bounded by their sequential budget (approximately four
  seconds, plus local scheduling overhead), rather than waiting indefinitely.
- Context contributors enrich the one existing `AgentRuntimeContext` in request order, exactly once
  per execution. They cannot replace the context or execute Core work. A contributor failure before
  Core start produces no Core events; the outer coordination shell still stops renewal, releases
  the lease, and closes the session.
- Middleware orders 100 (coordination) and 200 (persistence) are semantic contracts, not incidental
  values. The real invocation order is coordination pre-check, persistence pre-check, Core,
  persistence post-check, coordination post-check. Equal middleware orders retain registration
  order through the extension kernel's stable sort.
- Persistence/coordination failure precedence is timing-independent. When both exist for an
  already-started operation, `ExecutionPersistenceException` remains primary. Coordination
  observations are suppressed diagnostics. Duplicate observations of the same explicit ownership
  loss are collapsed, while distinct unavailable failures from session, watchdog-stop, and release
  origins are retained.
- A completed Model or Tool operation keeps its truthful Core terminal event even when persistence
  or coordination fails at the following safe boundary. Future Model or Tool work is fail-stopped;
  established facts are never rewritten.
- Scheduler shutdown, slow renewal, and shared capacity affect availability only. They do not make
  Redis an execution-truth authority and do not introduce recovery, replay, takeover, or external
  side-effect fencing.

### Deferred concerns

P7 intentionally does not implement:

- production activation of the coordinated execution flow;
- recovery, replay, retry, cancellation, pause/resume, or streaming;
- session-level serialization, queuing, or new-message interruption;
- Redis Pub/Sub or Streams;
- fencing tokens/epochs or external-resource fencing;
- tool idempotency, distributed transactions, or exactly-once external side effects.

These belong to later P7/P8/P10 phases.

## Consequences

The coordination contract remains deterministic and Redis-neutral, while the adapter maps its
explicit outcomes to atomic Redis commands. Long-running ownership is maintained by a shared
watchdog scheduler, and the execution-scoped session retains its small monotonic lifecycle. Runtime
safe boundaries fail-stop future cooperative work without rewriting Core history. Production
activation, recovery, cancellation, and external-resource fencing remain outside P7.
