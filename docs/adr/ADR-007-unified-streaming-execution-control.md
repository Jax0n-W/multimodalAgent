# ADR-007: Unified Streaming and Execution Control Contract

- Status: Accepted for P8.1 contract foundation
- Date: 2026-09-17

## Context

The frozen Runtime Core produces authoritative Model, Tool, and Run facts through `AgentEvent` and
returns one terminal `AgentRunResult`. P6 projects those facts into durable history. P7 protects
ephemeral active ownership with a Redis lease. None of those contracts provides a unified live
stream for clients or a semantic contract for requesting cancellation.

P8 will eventually support:

```text
Runtime -> live execution stream -> client
client  -> execution control intent -> Runtime
```

P8.1 defines only the semantic foundation and type contracts. It does not integrate a streaming
model provider, create an SSE endpoint or StreamHub, connect cancellation checkpoints to the
Runtime, or implement distributed control.

## Problem

Model output fragments, Runtime facts, and control observations must be visible in one ordered live
projection without turning that projection into another execution state machine. Cancellation must
be expressible without interrupting an already-started operation, bypassing Tool Governance,
rewriting terminal facts, or collapsing P6/P7 failure semantics.

The contract must also preserve the existing dependency direction: Runtime may understand generic
execution-control semantics, but it must not know about HTTP, SSE, WebFlux, Reactor, Redis,
Pub/Sub, controllers, persistence adapters, or node identity.

## Decision

### Authority boundaries

Five authorities remain separate:

```text
Runtime Core
    = Model / Tool / Run execution truth

MySQL
    = successfully persisted durable history
      + durable run identity

Redis Run Lease
    = ephemeral active execution ownership

Unified Execution Stream
    = live observation and projection only

Execution Control
    = intent requesting future execution behavior
```

A stream message cannot decide whether Runtime succeeded or failed. A cancellation marker cannot
directly mutate durable Run state. Redis control state cannot become the Runtime state machine. An
SSE connection cannot own the execution lifecycle.

### Live projection envelope

`ExecutionStreamEvent` is an immutable envelope with:

```text
runId
streamSequence
occurredAt
kind
typed payload
```

The allowed kinds are:

```text
RUNTIME_EVENT
MODEL_DELTA
CONTROL_EVENT
```

Payloads form a sealed hierarchy:

- `RuntimeEventPayload` holds the original `AgentEvent` object without copying or changing it;
- `ModelDelta` represents an observed provider text fragment;
- `ControlEvent` represents observed execution-control state.

The envelope rejects missing identity, non-positive sequence, missing timestamp, missing kind or
payload, kind/payload mismatch, and a wrapped Runtime event from a different `runId`. It does not
use `Map<String, Object>` or untyped payloads.

For `RUNTIME_EVENT`, the envelope keeps the wrapped fact's original `occurredAt`; for Model and
Control observations, `occurredAt` is the observation time supplied by the future live publisher.
This timestamp does not create a second ordering authority: ordering is defined by
`streamSequence`.

The stream package is outside `agent.runtime`. It may depend inward on Runtime facts and generic
control types. Runtime must never depend outward on the live-stream contract.

### Runtime event sequence and stream sequence

`AgentEvent.sequence` remains the order of authoritative Runtime Core facts. It is unchanged and
never contains model deltas or control observations.

`ExecutionStreamEvent.streamSequence` orders all live observations emitted for one `runId`:

```text
streamSequence=1  RUNTIME_EVENT  RUN_STARTED
streamSequence=2  RUNTIME_EVENT  MODEL_STARTED
streamSequence=3  MODEL_DELTA    "Hello"
streamSequence=4  MODEL_DELTA    " world"
streamSequence=5  RUNTIME_EVENT  MODEL_COMPLETED
```

One live execution stream for one runId has exactly one streamSequence authority.
Individual RuntimeEvent, ModelDelta, and ControlEvent producers do not own independent sequence spaces.

That single run-scoped authority assigns every envelope sequence, regardless of whether the
observation originated from a Runtime fact, Model provider, or Control boundary. The shared
sequence starts at 1, is strictly increasing, and has no duplicate value within the logical live
stream for that Run. Producers submit observations to this authority; they do not allocate their
own counters. Multiple subscribers observe the same logical sequence space rather than creating a
new sequence space per subscriber or producer.

P8.1 validates the value boundary and freezes sequence ownership, but deliberately does not
implement the run-scoped sequencer or publisher that will enforce this contract in P8.3.

This is not a distributed, globally durable order. P8.1 does not promise replay after reconnect,
cross-node sequence continuation, Redis-backed history, or gap repair.

### Model delta semantics

`MODEL_DELTA` is a provider-output observation, not a Runtime execution fact. It cannot replace
`MODEL_COMPLETED` or `MODEL_FAILED`, produce a Runtime transition, change `AgentRunResult`, or be
durably interpreted as a completed Model response.

Future P8.2 streaming adapters must follow this shape:

```text
provider stream
  -> emit MODEL_DELTA observations
  -> accumulate provider output
  -> construct one complete ModelTurn
  -> return ModelTurn to the existing AgentRunner
```

`AgentRunner` remains a `ModelTurn` consumer and must not become a token-driven state machine.

### Tool-call accumulation rule

Provider streams may fragment a ToolCall ID, name, or arguments. A partial ToolCall is only
provider protocol state. It must never enter `ToolExecutor` or trigger an external side effect.

Future adapters must accumulate and validate the complete provider ToolCall, construct a complete
`ToolCall`/`ModelTurn`, and return it to `AgentRunner`. The existing P3 path then remains mandatory:

```text
complete ModelTurn
  -> ToolRegistry resolve
  -> deserialize
  -> Jakarta Validation
  -> Tool Policy
  -> cancellation checkpoint
  -> TOOL_STARTED
  -> AgentTool.execute
```

Streaming cannot bypass Tool Governance.

### ExecutionControl semantics

`ExecutionControl` is a provider-neutral and infrastructure-neutral cancellation-intent contract.
It extends the existing `CancellationContext`, so it can be propagated through the existing Runtime
context without modifying frozen execution components.

Its state axis is deliberately minimal:

```text
RUNNING -> CANCEL_REQUESTED
```

`requestCancel()` is atomic, monotonic, and idempotent:

- the first request returns `ACCEPTED`;
- later requests return `ALREADY_REQUESTED`;
- `CANCEL_REQUESTED` cannot transition back to `RUNNING` within the same execution.

`CancelRequestResult` also reserves boundary outcomes for a future active-execution service:

```text
ACCEPTED
ALREADY_REQUESTED
ALREADY_TERMINAL
NOT_ACTIVE
```

The local `ExecutionControl` does not determine whether a Run is durable, terminal, or registered
on another node. A future outer control service owns `ALREADY_TERMINAL` and `NOT_ACTIVE`.

### Cooperative cancellation

Cancellation is cooperative. `requestCancel()` does not mean:

```text
Thread.interrupt()
Future.cancel(true)
kill provider HTTP request
kill Tool invocation
mutate AgentRunEntity
delete or invalidate a Redis lease
```

An already-started Model or Tool operation retains a truthful terminal Core fact:

```text
MODEL_STARTED -> MODEL_COMPLETED or MODEL_FAILED
TOOL_STARTED  -> TOOL_SUCCEEDED or TOOL_FAILED
```

Cancellation may only prevent future work at an explicit safe checkpoint. P8.1 defines this rule
but does not wire checkpoints into `AgentRunner` or `ToolExecutor`.

### Safe checkpoints

The frozen checkpoint vocabulary is:

```text
BEFORE_MODEL
AFTER_MODEL
BEFORE_TOOL_EXECUTION
AFTER_TOOL_EXECUTION
```

The future `BEFORE_TOOL_EXECUTION` check belongs after Tool resolution, deserialization,
validation, Policy evaluation, and `ALLOW`, but before `TOOL_STARTED` and the actual side effect:

```text
resolve -> deserialize -> validate -> policy(ALLOW)
  -> BEFORE_TOOL_EXECUTION
  -> TOOL_STARTED
  -> AgentTool.execute
```

Cancellation therefore neither skips nor redefines Tool Governance. The before/after Model and
Tool checks stop only work that has not yet started.

### CANCELLED Runtime outcome

Explicit cancellation is a legitimate future Runtime termination reason, unlike a P7
infrastructure ownership failure. `AgentStopReason.CANCELLED` is therefore the frozen Core outcome
for a cancellation observed at a safe checkpoint. Future P8.4 integration may emit:

```text
RUN_STOPPED(CANCELLED)
```

P8.1 does not emit that event and does not alter `AgentRunner`. Cancellation must never be mapped to
`MODEL_ERROR`, `TOOL_ERROR`, or `INTERNAL_ERROR` merely because the intent was observed.

### Late and repeated cancellation

Once the current Core invocation has established its outcome—including `RUN_COMPLETED`,
`RUN_STOPPED(CANCELLED)`, another `RUN_STOPPED` reason, or `RUN_WAITING_APPROVAL`—a later control
observation cannot rewrite that already-established Core fact. For a completed invocation, the
future control boundary returns `ALREADY_TERMINAL` rather than changing `COMPLETED` into
`CANCELLED`.

`RUN_WAITING_APPROVAL` is a stable outcome of the current invocation or execution segment. It is
not a declaration that the durable Run is permanently terminal or can never continue. A future
approval, resume, or recovery flow may begin a later execution segment without retroactively
rewriting the invocation that entered `WAITING_APPROVAL`. P8.1 does not implement that continuation.

Repeated cancellation before terminal completion is harmless and returns `ALREADY_REQUESTED`.
`NOT_ACTIVE` means the control boundary cannot identify an active or terminal execution for the
requested Run; P8.1 does not implement the registry needed to distinguish these cases.

### Client disconnect semantics

Transport lifecycle and execution lifecycle are independent:

```text
SSE disconnect != cancel Run
```

A browser refresh, mobile network interruption, client timeout, or explicit stream unsubscribe
must not call `ExecutionControl.requestCancel()` implicitly. Cancellation requires an explicit
control request.

### Streaming failure semantics

Subscriber failure, slow-client behavior, SSE write failure, and subscriber disconnect affect
observation availability only. They must not change `AgentRunResult`, `AgentStopReason`, Runtime
events, Tool policy, persistence projections, or lease ownership.

The stream publisher must isolate subscriber/transport failures from the execution producer. P8.1
does not implement this publisher.

### Backpressure boundary

Runtime must not be blocked indefinitely by a slow live-stream subscriber. Future P8.3/P8H work may
use bounded subscriber buffers, disconnect slow subscribers, and define drop behavior only for
semantically droppable observations. It must not silently drop authoritative Runtime facts while
claiming a complete stream.

P8.1 does not introduce Reactor, Flux, buffer policy, or an SSE transport implementation.

### Distributed-control boundary

Distributed cancellation belongs to P8.5. P8.1 does not create Redis cancel markers, Pub/Sub,
Streams, a distributed active-control registry, or takeover behavior.

Redis Run Lease and future Redis Control are independent:

```text
Redis Run Lease       = execution ownership
Future Control Marker = cancellation intent
```

They must not share a key, value, token, or lifecycle. A future namespace candidate is:

```text
mma:control:v1:run:{runId}:cancel
```

P8.1 does not create or access that key.

### Failure precedence

Failure precedence is phase-aware, not a global ranking.
Core execution truth and caller-visible infrastructure outcome are distinct layers.

Once a Core terminal fact has been established, no later persistence, coordination, streaming, or
control observation may emit or synthesize a different Core terminal fact. For
`WAITING_APPROVAL`, the same immutability applies to the established outcome of the current
invocation or execution segment; it does not make the durable Run permanently terminal.

Core-fact immutability does not mean that every infrastructure failure observed after a Core fact
is diagnostic-only. Caller-visible infrastructure failure precedence remains governed by the
frozen P6/P7 execution-shell contracts until the outer execution lifecycle has completed. P8 does
not create a new global ranking and does not redefine those caller-visible contracts.

Before the current Core invocation outcome has been established:

- the frozen P6/P7 caller-visible failure contracts continue to apply;
- cancellation is control intent and cannot hide a persistence or coordination failure that has
  already occurred;
- an already-started Model or Tool still records its truthful terminal fact;
- if cancellation is observed at a safe checkpoint before new work starts, and no P6/P7 boundary
  failure governs that point, future P8.4 may establish `RUN_STOPPED(CANCELLED)`.

After the current Core invocation outcome has been established:

- later observations cannot synthesize a second or different Core outcome;
- P6 persistence or finalization failures may remain caller-visible exactly as ADR-005 defines,
  without rewriting the Core fact;
- P7 watchdog-stop, coordination-boundary, and execution-shell failures retain their existing
  caller-visible or suppressed behavior;
- streaming transport failures and late control observations remain observation/control concerns
  and cannot replace the established Core fact.

Failures already classified by P7 as terminal cleanup diagnostics remain diagnostic-only. In
particular, after successful Core execution and durable finalization, a Redis compare-and-release
failure remains a terminal cleanup diagnostic and does not replace the Core result. P8 does not
broaden that category.

P8 does not reclassify P7 watchdog-stop, coordination-boundary, or execution-shell failures. A
`watchdog.stop()` call establishes the linearization boundary with an in-flight renewal and protects
stop-before-release ordering. Its existing P7 failure behavior therefore remains distinct from a
Redis release failure and is not declared diagnostic-only by this ADR.

### Three independent state axes

P8 must not create one aggregate super-state. The axes answer different questions:

| Axis | Type | Question |
|---|---|---|
| Ownership | `RunLeaseSession` | Does this execution still own the Run? |
| Control | `ExecutionControl` | Has cancellation been requested? |
| Runtime result | `AgentRunResult` / `AgentStopReason` | Why did Core terminate? |

Ownership may be `LOST` while control remains `RUNNING`; cancellation may be requested while a Tool
finishes successfully; a terminal Runtime result remains final regardless of a later control
request.

### Architecture dependency rules

The allowed direction is:

```text
outer live-stream/control adapters
  -> stream contract
  -> Runtime facts / generic execution-control contract
```

Runtime may depend on its generic control contract. Runtime must not depend on the stream package,
controllers, Spring Web/WebFlux, Reactor, SSE adapters, Redis, Spring Data Redis, persistence
implementation, Pub/Sub, or node identity.

The stream contract itself remains transport- and infrastructure-neutral. ArchUnit enforces these
boundaries.

## Deferred concerns

P8.1 deliberately does not implement:

- real Ollama/OpenAI model streaming;
- changes to Spring AI adapters;
- token-driven Runtime execution;
- ToolCall fragment assemblers;
- StreamHub, SSE endpoint, Reactor/Flux integration, or backpressure policy;
- Runtime checkpoint wiring;
- thread, Future, HTTP, Model, or Tool interruption;
- Redis control markers, Pub/Sub, Streams, or distributed cancellation;
- pause, resume, retry, recover, replay, or takeover;
- checkpoint persistence, idempotency, external fencing, or `UNKNOWN` side-effect semantics.

These belong to P8.2 through P8.5, P8H, or P10.

## Consequences

Streaming is a live projection of execution, not an execution-truth authority. Runtime events keep
their own semantic sequence; the live stream uses an independent sequence to interleave Runtime
facts, model deltas, and control observations.

Cancellation is explicit, idempotent, monotonic, and cooperative. An operation that has already
started retains its truthful terminal Core fact. Transport failure, slow consumers, disconnects,
and late cancellation cannot rewrite execution truth. Durable persistence, distributed ownership,
live observation, execution control, and Runtime result remain separate semantic authorities.
