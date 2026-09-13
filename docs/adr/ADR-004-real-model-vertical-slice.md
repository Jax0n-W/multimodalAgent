# ADR-004: Real Model Vertical Slice

- Status: Accepted; runtime end-to-end verified
- Date: 2026-09-13

## Context

P5V connects the local fine-tuned `mindbridge-qwen2.5-7b-ft` model to the frozen Java Agent
Runtime through Spring AI and Ollama. The model is a Qwen2.5-7B GGUF quantized as Q4_K_M and
registered in Ollama as `mindbridge-qwen2.5-7b-ft:latest`.

The phase must prove the non-streaming path from `AgentExecutionCoordinator`, through a real model
ToolCall and governed Java Tool execution, back to a final model answer. It does not migrate the
production ChatService or add platform capabilities.

## Decisions

### 1. Runtime remains provider-agnostic

Runtime contracts contain no Spring AI, Ollama, or OpenAI types. `AgentModelRequest` carries
immutable runtime messages and provider-neutral `ModelToolDefinition` values.

### 2. The adapter is the provider integration boundary

`SpringAiOllamaAgentModelAdapter` owns Spring AI request/response mapping, finish-reason handling,
JSON argument syntax parsing, and usage extraction. The optional
`SpringAiOpenAiAgentModelAdapter` shares the same protocol mapping without changing Runtime.

### 3. Ollama is reached through its local OpenAI-compatible endpoint

The actual provider remains local Ollama at `http://127.0.0.1:11434`, and the actual model is
`mindbridge-qwen2.5-7b-ft`. The adapter supplies a Spring AI `OpenAiChatModel` configured with this
local base URL; no OpenAI service or credential is used.

This transport choice is required because Spring AI 1.0.0's native `OllamaChatModel` maps native
Ollama ToolCalls to Spring AI ToolCalls with an empty ID. A nonblank provider ToolCall ID is a
frozen Runtime invariant used by approval, event correlation, run-wide uniqueness, and second-call
ToolResult correlation. Ollama's OpenAI-compatible endpoint preserves that provider ID. Spring AI
is not upgraded in P5V.

### 4. Spring AI never executes Tools

Spring AI receives definition-only `ToolCallback` values with internal Tool execution explicitly
disabled. A callback throws if invoked, so accidental framework-side execution fails closed.

### 5. ToolExecutor remains the only Tool execution path

Provider ToolCalls return as runtime `ToolCall` values. The existing path remains:

```text
ToolRegistry -> Deserialize -> Validation -> Policy -> RuntimeMiddleware -> Tool.execute
```

### 6. Only current-run allowedTools are exposed

`AgentRunner` intersects registered descriptors with `AgentRunSpec.allowedTools` before each model
invocation. This least-privilege projection supplements, but never replaces, ToolExecutor policy
enforcement.

### 7. Provider ToolCall identity and order are preserved

The provider ToolCall ID, Tool name, and call order are copied unchanged into Runtime. Missing IDs,
unsupported call types, and malformed protocol data fail at the model boundary. Existing run-wide
ToolCall ID uniqueness remains enforced by AgentRunner.

### 8. Syntax parsing and semantic validation remain separate

The adapter parses provider argument JSON into `Map<String, Object>`. It does not apply Bean
Validation or business rules. Strong typing, Jakarta Validation, and policy remain in ToolExecutor.

### 9. Provider usage maps to TokenUsage

Prompt and completion token counts map to Runtime input and output tokens. Missing provider usage
uses the explicit `TokenUsage.ZERO` fallback. Budget and quota enforcement remain deferred.

### 10. Unsupported provider outcomes are model failures

Only `STOP` without ToolCalls and `TOOL_CALLS` with ToolCalls are accepted. Transport errors,
missing or malformed responses, unsupported call types, malformed arguments, and unsupported
finish reasons throw from the adapter. AgentRunner converts these to `MODEL_FAILED` followed by
`RUN_STOPPED(MODEL_ERROR)`.

### 11. Real-model tests are opt-in

Tests tagged `real-model` are excluded from default Maven execution and enabled only with the
`real-model` profile. They first inspect Ollama `/api/tags` and skip with an explicit reason when
the endpoint or model is unavailable. Tests never start, stop, or reconfigure Ollama.

Defaults:

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_MODEL=mindbridge-qwen2.5-7b-ft
```

No external API key is required.

### 12. Verification starts at AgentExecutionCoordinator

Both real smoke cases enter through `AgentExecutionCoordinator`, not directly through the adapter:

1. Direct answer: one real model invocation, `STOP`, nonblank answer, `RUN_COMPLETED`, no Tool.
2. Tool round trip: real ToolCall, one deterministic `knowledge_search` execution through
   ToolExecutor, ToolResult correlation by the provider ID, second real model invocation, `STOP`,
   nonblank answer, and final `RUN_COMPLETED`.

The second test records model requests and turns so it can prove that the second invocation
contains both the assistant ToolCall and its matching ToolResult.

### 13. P5V is non-streaming

The adapter uses one synchronous Spring AI `ChatModel.call` per Runtime model iteration. Streaming
and partial ToolCall protocols remain deferred.

### 14. P5V does not integrate persistence or production ChatService

No AgentRun, AgentStep, ToolExecution, checkpoint, or recovery persistence is connected in this
phase. The existing ChatService, controllers, SSE flow, memory, RAG, and legacy `AiClient` remain
unchanged.

## Consequences

- The local fine-tuned Ollama model can propose Tools without bypassing Runtime governance.
- The provider ToolCall ID survives the full model-to-runtime-to-model round trip.
- Tool schemas are generated from Java input types at the adapter boundary.
- Default CI remains deterministic and performs no real model network calls.
- Successful deterministic adapter tests do not by themselves prove the real vertical slice;
  P5V freezes only after both coordinator-level real smoke tests pass.

## Verification

On 2026-09-13, `mvn -Preal-model -Dtest=RealModelAgentSmokeTest test` ran against the local
Ollama model and passed both tests without skips:

- Direct answer: one model invocation, `STOP`, nonblank answer, final `RUN_COMPLETED`, no Tool.
- Tool round trip: first turn `TOOL_CALLS`, one `knowledge_search` execution through ToolExecutor,
  provider ID preserved into the assistant ToolCall and ToolResult, second turn `STOP`, nonblank
  answer, final `RUN_COMPLETED`.

The local model's native Tool Calling and ToolResult follow-up behavior had also been verified
independently before the Runtime end-to-end test. The coordinator test is the P5V freeze evidence.

## Deferred

P6 persistence integration, P7 Redis coordination, P8 streaming, P9 gateway/budget, P10 recovery,
P11 memory, P12 RAG, and P13 production hardening remain out of scope.
