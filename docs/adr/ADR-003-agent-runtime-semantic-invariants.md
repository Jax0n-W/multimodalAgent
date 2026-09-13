# ADR-003: Agent Runtime Semantic Invariants

- Status: Accepted
- Date: 2026-09-13

## Context

Phase 4 established the Agent Runtime Core event contract and Decision Trace. Phase 5 added a
synchronous, transparent extension kernel around run, model, and allowed tool execution. P5H
freezes the combined semantics so that events remain facts that actually happened when failures
occur before or after core work, when a model returns multiple tool calls, and when the model loop
reaches its iteration limit.

This ADR records the behavior derived from the current implementation and locked by tests. It does
not add durable execution, recovery, retry, cancellation, or parallel tool execution.

## Decisions

### 1. Run terminality

Once the Agent Runtime Core starts, it emits `RUN_STARTED` with iteration `0` and exactly one of:

- `RUN_COMPLETED` for a final `STOP` model turn;
- `RUN_STOPPED` for an error, policy denial, or maximum-iteration stop;
- `RUN_WAITING_APPROVAL` for a `REQUIRE_APPROVAL` decision.

The terminal event is the final core event. `RUN_WAITING_APPROVAL` is the frozen Phase 4 pause
terminal and is the explicit exception to the simplified `RUN_COMPLETED XOR RUN_STOPPED` wording.
If `aroundRun` fails before proceeding, the core never starts and therefore emits no core events.
If it fails after the core has completed, the harness propagates the extension failure without
adding or rewriting a core terminal event.

### 2. Model invocation terminality

Every model invocation that actually starts has exactly one terminal fact:

```text
MODEL_STARTED -> MODEL_COMPLETED
MODEL_STARTED -> MODEL_FAILED
```

Model middleware failure before `proceed()` creates no model event and stops the run with
`INTERNAL_ERROR`. A real model failure creates `MODEL_FAILED` and stops with `MODEL_ERROR`, even if
the model throws an exception class whose name resembles a middleware exception. Model middleware
failure after `MODEL_COMPLETED` preserves the completion fact and stops the run with
`INTERNAL_ERROR`; it does not append `MODEL_FAILED`.

A `STOP` completion declares zero tool calls. A `TOOL_CALLS` completion declares at least one, and
its declared count must equal the `TOOL_REQUESTED` facts for that iteration. The only count-mismatch
exception is model middleware failure immediately after a completed model call, before the runner
can publish requests.

### 3. Tool execution terminality

The governed pipeline remains:

```text
resolve -> deserialize -> validate -> policy -> ALLOW
        -> aroundToolExecution -> TOOL_STARTED
        -> execute -> serialize -> TOOL_SUCCEEDED / TOOL_FAILED
```

Unknown tools, invalid arguments, `DENY`, and `REQUIRE_APPROVAL` never enter tool middleware and
never emit `TOOL_STARTED`. Once `TOOL_STARTED` occurs, exactly one of `TOOL_SUCCEEDED` or
`TOOL_FAILED` records its core outcome.

Tool middleware failure before `proceed()` occurs after `ALLOW` but before `TOOL_STARTED`, and the
run stops with `INTERNAL_ERROR`. A real tool failure emits `TOOL_FAILED` and stops with
`TOOL_ERROR`. Tool middleware failure after `TOOL_SUCCEEDED` preserves the success fact and stops
the run with `INTERNAL_ERROR`; it never rewrites success as `TOOL_FAILED`.

### 4. Event ordering and forbidden traces

Event sequence numbers start at `1`, are contiguous within one run, and all events share the same
`runId`. Model iterations start at `1` and increase by one. Tool lifecycle events correlate by
`runId`, iteration, tool name, and a run-unique `toolCallId`.

The Decision Trace rejects, among other invalid histories:

- a second or non-initial `RUN_STARTED`, a missing terminal, multiple terminals, or events after a
  terminal;
- model completion/failure without a matching start, dual model terminals, or continued model work
  after a model failure;
- tool requests after a `STOP` turn or a request count different from the model declaration;
- validation/policy/start/success/failure transitions in an impossible order;
- terminal stop reasons that contradict the immediately preceding model, tool, or policy cause;
- `RUN_COMPLETED` without a final `MODEL_COMPLETED(STOP)`.

### 5. Multi-tool ordering

For one `ModelTurn`, tool calls preserve model order. The runtime first publishes every
`TOOL_REQUESTED` in that order, then executes the calls synchronously and serially in the same
order. Tool A reaches its terminal tool event before Tool B starts; Tool B reaches its terminal
tool event before Tool C starts. Tool-result messages are appended to the next model request in
that same order.

Execution is fail-fast. If A succeeds, B fails, and C is pending:

```text
A -> TOOL_SUCCEEDED
B -> TOOL_FAILED
C -> requested but NOT_STARTED
Run -> RUN_STOPPED(TOOL_ERROR)
```

C is not executed. The earlier success remains a success in both events and Decision Trace.

### 6. Partial execution truth

Parent failure never rewrites completed child facts. A later tool failure does not change an
earlier `MODEL_COMPLETED` or `TOOL_SUCCEEDED`. Likewise, a middleware post-phase failure can change
the run or harness outcome but cannot fabricate a model or tool failure after success.

### 7. Iteration accounting

An iteration is a model-loop slot numbered from `1`. `AgentRunResult.iterations` counts model
invocations that actually started:

- model middleware failure before `proceed()` does not consume a completed/start count and reports
  the preceding count (`0` in the first slot), although `RUN_STOPPED` carries the attempted slot;
- real model failure consumes the started invocation;
- model middleware failure after completion consumes the invocation and preserves its token usage;
- tool and tool-middleware outcomes occur inside the iteration of the model turn that requested
  them.

### 8. Maximum-iteration semantics

`maxIterations` is the maximum number of real model invocation slots, not the maximum number of
tool calls. The loop is inclusive from `1` through `maxIterations`; tests at `1`, `2`, and `3`
prove there is no off-by-one call. If the last allowed model turn requests tools, those tools are
processed normally and the run then stops with `MAX_ITERATIONS` without starting another model
invocation.

### 9. Model output identity and validity

`ModelTurn` rejects contradictory `STOP + tool calls` and `TOOL_CALLS + empty calls` states. It
rejects null tool elements through immutable list construction and rejects duplicate ToolCall IDs
within one turn. `AgentRunner` also rejects reuse of a ToolCall ID across later iterations of the
same run, classifying the invalid model output as `MODEL_ERROR` before publishing
`MODEL_COMPLETED`. This keeps approval lookup and event correlation unambiguous.

For a `STOP` turn, a null tool-call list is normalized to an empty immutable list; null content and
token usage retain their existing normalization semantics.

### 10. Middleware/core failure distinction

Failure classification is origin-based:

```text
middleware failure -> INTERNAL_ERROR
real model failure -> MODEL_ERROR
real tool failure -> TOOL_ERROR
```

Transparent middleware remains synchronous, invocation-scoped, thread-confined, exactly-once, and
same-result identity preserving. P5H does not extend this contract.

## Consequences

- Runtime event streams and Decision Traces have one deterministic interpretation.
- Approval and tool-event correlation rely on ToolCall IDs that are unique for the entire run.
- Multi-tool processing is deterministic, ordered, and fail-fast while preserving partial truth.
- The iteration limit is testable as a strict upper bound on model invocations.
- Core truth and outer harness outcome remain deliberately separate.

## Deferred

Future phases may define durable `UNKNOWN` outcomes, persistence projection, checkpoint/resume,
real cancellation, retries, idempotency, locks, rate limits, RAG/memory, asynchronous middleware,
or parallel tool execution. Those capabilities must preserve the facts and ordering rules above;
none is implemented by P5H.
