# ADR-008: Model Streaming Core

- Status: Accepted for P8.2
- Date: 2026-09-18

## Context

The Runtime Core consumes one complete `ModelTurn` per model invocation. P8.1 introduced
observation-only `ModelDelta` values but deliberately did not connect a provider stream, sequence
live observations, or change Agent control flow.

Spring AI 1.0.0 exposes OpenAI-compatible streaming at two levels. The wire protocol contains
incremental `ChatCompletionChunk.delta` fields with provider ToolCall indexes. Its existing
`OpenAiApi.chatCompletionStream()` helper merges function-call chunks before returning them and the
merge path does not preserve every fragment index. It also rejects some multi-call chunk shapes.
That transformed representation cannot satisfy P8.2's strict interleaved ToolCall correlation
contract.

## Decision

P8.2 adds an adapter-layer OpenAI-compatible SSE client built with Spring WebClient and Spring AI's
request/response DTOs. No additional provider SDK is introduced. Reactor and WebClient remain in
`agent.adapter`; Runtime Core does not depend on them.

The adapter lifecycle is:

```text
AgentRunner
  -> AgentModel.generate(request)
  -> OpenAI-compatible streaming request
  -> raw SSE delta events
  -> StreamingTurnAccumulator
       -> ordered text aggregation + ModelDelta observation
       -> indexed incremental ToolCall assembly
       -> provider usage capture
       -> required finish reason + required [DONE]
  -> one complete ModelTurn
  -> AgentRunner
```

One provider streaming request remains one Agent model invocation and consumes one Runtime
iteration slot regardless of chunk count. `AgentRunner`, `AgentModel`, and `ModelTurn` are unchanged.

### Text observations

Every non-empty provider text fragment is appended without trimming and offered as one
`ModelDelta`. Whitespace-only fragments are preserved. Empty fragments are not fabricated into
observations. Tool argument fragments are internal assembly state and are not emitted as text.

`ModelDeltaObserver` is observation-only. The adapter catches observer runtime failures, records a
diagnostic warning, disables further observation for that invocation, and continues reconstructing
the provider result. Observation failure cannot become `MODEL_FAILED`.

`StreamingModelInvocationScope` is transparent Runtime middleware. A run registers its observer in
its own `AgentRuntimeContext`; at model-invocation entry the scope places the iteration and that
observer in a caller-thread `ThreadLocal`. The adapter reads the scope exactly once, before provider
subscription, and captures both values in an immutable local reference. Reactor callbacks use only
that captured reference and never read the `ThreadLocal`. A `finally` block removes the scope after
success or failure (or restores the previous value for a nested invocation), preventing thread-pool
reuse from seeing stale invocation state. It does not alter the Runtime model request or result.
P8.2 does not assign `streamSequence`; P8.3 owns that authority.

### ToolCall assembly

The raw OpenAI-compatible wire representation is incremental. Fragments are grouped by the
provider-supplied ToolCall index, accumulated independently, and returned in provider index order.
Provider IDs are accumulated and preserved exactly. No UUID or index-derived ID is created.

Only after the stream supplies a supported terminal finish reason and `[DONE]` does the assembler
require complete ID, name, type, and arguments. Requiring `[DONE]` is the framing contract of the
currently supported Ollama OpenAI-compatible SSE adapter; it is not asserted as a universal rule
for every OpenAI-compatible provider. Arguments are parsed into the existing Runtime
`Map<String, Object>` representation. A missing ID, missing name, missing index, unsupported type,
or malformed JSON fails the model invocation closed. No partial ToolCall can reach `ToolExecutor`.
The existing P3 resolve/deserialize/validate/policy/execute path remains the only execution path.

### Completion and failure

`STOP` produces one complete text `ModelTurn`. `TOOL_CALL` and `TOOL_CALLS` produce one complete
tool-call `ModelTurn`. Other finish reasons, missing finish reason, missing `[DONE]`, malformed
frames, assembly failures, and provider transport errors fail the model invocation. Previously
observed partial deltas never turn an incomplete stream into success.

Provider usage is captured only when a usage chunk is present. It is never estimated. When the
provider supplies no usage, P8.2 uses the existing `TokenUsage.ZERO` fallback required by the frozen
`ModelTurn` contract; it is not represented or reported as measured provider usage. The
OpenAI-compatible protocol defines one authoritative final usage chunk, so duplicate usage chunks
fail closed instead of silently overwriting previously captured accounting. Consequently,
`TokenUsage.ZERO` may mean that provider usage was unavailable and must not be interpreted as a
provider-measured zero. A first-class unknown/measured usage distinction is deferred to P9
usage/budget semantics.

### Activation

The adapter and client require explicit construction. P8.2 does not replace the existing default
non-streaming model path. The real Ollama streaming smoke remains tagged `real-model` and is excluded
from default Maven/CI execution.

P8.2 validates ToolCall streaming deterministically with a protocol-level raw SSE fixture replayed
through the real WebClient parser, accumulator, and partial-call assembler. The fixture covers
provider indexes, interleaved calls, fragmented names and arguments, provider ID preservation, and
construction of one complete `ModelTurn.TOOL_CALLS`. It is deterministic wire-protocol evidence,
not a claim that a live fine-tuned model will choose a tool for every prompt. Live provider ToolCall
behavior remains an opt-in compatibility check when the local model/backend supports it reliably.

## Deferred

P8.2 does not implement stream sequencing, StreamHub, SSE delivery to clients, subscribers,
backpressure, reconnect/replay, Runtime cancellation checkpoints, provider abort, active execution
registry, Redis control markers, Pub/Sub, distributed cancellation, pause, resume, retry, recovery,
takeover, or external fencing.

Provider invocation deadlines and streaming timeout semantics are also deferred to the P9 Model
Gateway. P8.2 intentionally does not add Reactor timeout operators or timed blocking because those
would introduce new model-failure semantics.
