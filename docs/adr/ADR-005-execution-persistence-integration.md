# ADR-005: Execution Persistence Integration

- Status: Accepted
- Date: 2026-09-14

## Context and scope

P6 connects the frozen Runtime Kernel to the existing `agent_runs`, `agent_steps`, and
`tool_executions` tables. It adds durable admission, synchronous projection of Core events, and
terminal result finalization. It does not add recovery, retry, Redis coordination, idempotency
enforcement, streaming, or migrate `ChatService`.

The Runtime remains the authority for execution semantics and Core event truth. The database is
the authority only for history that was successfully recorded. P6 does not claim atomicity among
Core state, database commits, model-provider activity, and external tool side effects.

## Decision

### Integration boundary

`PersistentAgentExecutionCoordinator` wraps the existing `AgentExecutionCoordinator` for durable
admission and finalization. `ExecutionPersistenceEventPublisher` synchronously projects emitted
facts through `ExecutionHistoryStore`. `ExecutionPersistenceBoundaryMiddleware` observes recorded
infrastructure failures at the pre/post model and tool boundaries. Runtime classes do not import
the integration package, repositories, JPA, Hibernate, Spring AI infrastructure, or Redis.

The event emitter's frozen behavior catches publisher exceptions. Therefore the persistence
publisher records the first failure in a run-scoped registry instead of attempting to throw through
the emitter. The middleware stops future execution at the first semantically safe boundary, and the
outer coordinator exposes the original `ExecutionPersistenceException` to its caller.

Alternatives rejected were a transaction around the whole run, direct repository calls from the
Runtime, an asynchronous best-effort subscriber, and post-run-only persistence. They respectively
cross remote/side-effect boundaries, invert dependencies, hide durable-history loss, or cannot
fail-stop future work.

### Event-to-persistence mapping

| Core event | Run projection | Step / execution projection | Actual invocation? |
|---|---|---|---|
| `RUN_STARTED` | `RUNNING`, start time from `occurredAt` | none | Core run started |
| `MODEL_STARTED` | iteration, `MODEL_RUNNING` | create RUNNING MODEL step | model dispatch boundary reached |
| `MODEL_COMPLETED` | `FINALIZING` or `AWAITING_TOOL` | MODEL step SUCCEEDED | model completed |
| `MODEL_FAILED` | `FINALIZING` | MODEL step FAILED with Core stop reason | genuine model failure |
| `TOOL_REQUESTED` | iteration, `AWAITING_TOOL` | create PLANNED TOOL step and ToolExecution | no |
| `TOOL_VALIDATED` | iteration, `AWAITING_TOOL` | no state claim beyond planned | no |
| `TOOL_VALIDATION_FAILED` | `FINALIZING` | step SKIPPED, execution BLOCKED | no |
| `TOOL_POLICY_EVALUATED(ALLOW)` | iteration, `AWAITING_TOOL` | remains PLANNED until started | no |
| `TOOL_POLICY_EVALUATED(DENY)` | iteration, `AWAITING_TOOL` | step SKIPPED, execution BLOCKED | no |
| `TOOL_POLICY_EVALUATED(REQUIRE_APPROVAL)` | iteration, `AWAITING_TOOL` | remains PLANNED | no |
| `TOOL_STARTED` | iteration, `TOOL_RUNNING` | step RUNNING, execution STARTED | yes |
| `TOOL_SUCCEEDED` | iteration, `AWAITING_TOOL` | step/execution SUCCEEDED | completed successfully |
| `TOOL_FAILED` | `FINALIZING` | FAILED only for actual execution; unknown/invalid calls are BLOCKED | only when Core says execution failed |
| `RUN_COMPLETED` | COMPLETED and terminal time | none | terminal Core fact |
| `RUN_STOPPED` | FAILED/CANCELLED, stop reason and terminal time | none | terminal Core fact |
| `RUN_WAITING_APPROVAL` | WAITING_APPROVAL without completion time | planned tool remains unstarted | terminal Core fact |

`finalizeRun` verifies that the persisted terminal stop reason equals `AgentRunResult.stopReason`.
Only a completed run stores `finalContent`.

### Identity, order, and correlation

- Admission preserves `runId`, `requestId`, `sessionId`, and `userId` from the execution request.
- MODEL step identity is a deterministic name UUID over `(runId, iteration)`.
- TOOL step and execution identities are deterministic name UUIDs over `(runId, toolCallId)`.
- The provider/runtime `toolCallId` is stored unchanged; it is never regenerated.
- `stepIndex` is assigned from the persisted per-run step count and constrained unique by
  `(run_id, step_index)`. It reconstructs MODEL/TOOL order without database primary-key ordering.
- `iteration` always comes from the frozen Core event.

P6 assumes one active writer per run. Concurrent run coordination is intentionally deferred; a
race is surfaced by database uniqueness/optimistic-locking rules rather than silently overwritten.

### Transactions and optimistic locking

Admission, every event projection, and finalization each use a short `REQUIRES_NEW` transaction.
No transaction spans model inference, HTTP calls, or tool side effects. Existing `@Version` fields
on AgentRun and ToolExecution remain authoritative; optimistic-lock conflicts are persistence
infrastructure failures and are never classified as model or tool failures.

### Time semantics

Execution timestamps (`startedAt`, `completedAt`) come from `AgentEvent.occurredAt` and represent
when the Core fact occurred. The SQL schema stores these at `TIMESTAMP(6)` precision. Entity
`createdAt`/`updatedAt` values come from JPA callbacks and represent persistence metadata only; they
must not be interpreted as event occurrence times.

### Failure semantics

Durable admission is required before Core starts. If it fails, model/tool invocation counts remain
zero, no `RUN_STARTED` is emitted, and the caller receives `ExecutionPersistenceException`.

If persistence fails after a Core fact, that fact is immutable. In particular, persistence failure
must never create `MODEL_COMPLETED -> MODEL_FAILED`, `TOOL_SUCCEEDED -> TOOL_FAILED`, or
`RUN_COMPLETED -> RUN_STOPPED`. A model or tool whose frozen STARTED fact has already been emitted is
allowed to reach its terminal Core fact; the post-operation middleware boundary then prevents the
next action. If `RUN_COMPLETED` has already occurred, it remains the final Core event.

The outer coordinator always makes the persistence failure visible. It is not `MODEL_ERROR` or
`TOOL_ERROR`. No `PERSISTENCE_ERROR` is added to `AgentStopReason`, because that enum describes Core
outcomes and a database failure may happen after a completed Core outcome. The existing frozen
middleware translation may produce a Core `RUN_STOPPED(INTERNAL_ERROR)` when future execution is
halted at a middleware boundary, while the caller still receives the distinct persistence
exception.

`MODEL_STARTED` is emitted immediately before the real `model.generate` call; persistence failure
at that callback cannot prevent the already-started operation and is checked after its terminal
event. `TOOL_STARTED` is emitted immediately before `AgentTool.execute`; the same rule applies.
Thus P6 does not introduce false STARTED records or redefine frozen lifecycle semantics.

### Privacy and schema

P6 persists execution metadata and the required completed `finalContent`, but not full user
conversation, prompts, model request/response bodies, chain-of-thought, raw tool arguments, or raw
tool results. Existing optional hash/summary columns are not populated with sensitive raw data.
Only structured Core error codes are projected; `errorMessage` remains empty because the current
event contract has no separately sanitized message source.

V1 and V2 already contain every required column, enum representation, unique constraint, foreign
key, timestamp, and version field. They remain immutable and no V3 migration is needed. Existing
empty-schema and V1-to-V2 upgrade tests remain the migration contract.

### Deferred concerns

Retry is deferred because replay policy depends on side-effect and idempotency semantics. Recovery
and reconciliation are deferred because P6 cannot infer or repair partially observed external side
effects. Redis coordination is deferred because durable history does not require a second state
authority. Idempotency enforcement is deferred to the phase that defines admission/replay behavior;
P6 only preserves the existing request and tool correlation fields.

### Enforcement and test contract

ArchUnit rejects any dependency from `agent.runtime..` to persistence/repository/application
layers, JPA/Hibernate, Spring Data/Spring AI, or Redis. Deterministic tests must cover direct success,
tool round trip, model/tool failure, policy denial, waiting approval, max iterations, multi-tool
order, timestamps, and all specified persistence-failure boundaries. The full pre-P6 deterministic
suite must remain green.

## Consequences

Successful executions now have queryable durable run, model-step, tool-step, and tool-execution
history while the Runtime stays framework- and persistence-neutral. The deliberate limitation is
that a Core fact can exist without a matching durable row after a database failure; P6 exposes and
fail-stops that condition but does not retry, recover, or claim cross-system atomicity.
