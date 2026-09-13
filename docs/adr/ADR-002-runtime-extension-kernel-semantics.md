# ADR-002: Runtime Extension Kernel Semantic Boundaries

- Status: Accepted
- Date: 2026-09-13

## Context

Phase 5 adds a reusable extension seam around Agent runs, model calls, and allowed tool execution.
The seam must support observability without becoming a second runtime, weakening tool governance,
or rewriting core facts after they occur.

## Decisions

### 1. Middleware is a transparent extension

`RuntimeMiddleware` must call `next.proceed()` exactly once and return the same downstream object
instance. It cannot short-circuit, replace results, or change Agent core semantics. Metrics, tracing,
logging, latency/token observation, and context enrichment fit this boundary.

The `next` capability is synchronous and valid only within the dynamic scope and thread of the
corresponding middleware invocation. The extension kernel closes it whenever the middleware returns,
validation fails, or an exception leaves the invocation. A retained or cross-thread `next` cannot
start downstream work after that boundary.

Request idempotency, session-lock rejection, admission control, rate-limit rejection, cancellation
prechecks, and cached-result returns require a future Execution Guard / Preflight boundary. They do
not belong in transparent middleware.

### 2. Core outcome and harness outcome are distinct

Agent events describe the Agent Runtime Core lifecycle, not every application or harness
post-processing failure. Once `RUN_COMPLETED` is emitted, an `aroundRun` post-phase failure is a
harness/extension failure. It is propagated to the caller without emitting `RUN_STOPPED`, changing
the completed core outcome, or creating a second terminal event.

### 3. Failure classification is origin-based

`RuntimeMiddlewareFailureException` marks failures positively identified by the extension kernel as
middleware-originated. A downstream model or tool exception is rethrown unchanged by the middleware
chain. Consequently, a model or tool that happens to throw `RuntimeMiddlewareException` is still
classified as `MODEL_ERROR` or `TOOL_ERROR`; exception-name collision does not turn it into
`INTERNAL_ERROR`.

### 4. Tool governance precedes middleware

Tool middleware is entered only after resolve, deserialize, validation, policy evaluation, and an
`ALLOW` decision. Unknown tools, invalid arguments, `DENY`, and `REQUIRE_APPROVAL` never enter the
tool middleware chain. Middleware is not a safety bypass.

### 5. External side-effect truth outranks later failures

Once `TOOL_SUCCEEDED` is emitted, later middleware or harness failure cannot reinterpret the tool as
failed. The Decision Trace may therefore truthfully contain a succeeded tool and a run stopped with
`INTERNAL_ERROR`.

Three future persistence facts must remain distinct:

- Tool Invocation Truth: whether an external side effect occurred.
- Tool Result Processing Truth: whether its result was serialized and processed.
- Run Truth: whether the overall Agent run completed.

The current synchronous executor calls the external tool before serializing its result. A
non-idempotent side effect may therefore succeed while serialization fails. Future persistence
integration must represent ambiguous processing as durable `UNKNOWN` semantics and must never blind
retry a non-idempotent operation. Phase 5 deliberately does not implement durable tool execution,
recovery, retry, outbox, or checkpoint behavior.

### 6. Cancellation is propagation only

`CancellationContext` is a context-propagation seam. Phase 5 defines no interruption, cancellation
event, Redis flag, thread cancellation, or tool cancellation semantics.

## Consequences

- `AgentRunner` remains the sole owner of the Model-to-Tool reasoning loop.
- `ToolExecutor` remains the owner of tool contract validation and policy enforcement.
- `AgentExecutionCoordinator` creates one run context and applies `aroundRun`; it does not duplicate
  model, policy, tool, event, persistence, or recovery logic.
- Middleware instances and chains may be shared across runs; per-run mutable state belongs in
  `AgentRuntimeContext.attributes()`.
- Production `requestId` enforcement is deferred until the idempotency guard is integrated; minimal
  and test executions may omit it.
