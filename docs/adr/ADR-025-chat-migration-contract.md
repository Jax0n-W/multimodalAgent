# ADR-025: Legacy Chat Migration to Unified Agent Runtime

- Status: Accepted
- Date: 2026-10-10
- Phase: P11.4
- Builds on: ADR-022, ADR-023, and ADR-024

## Legacy / Unified execution boundary

The student-facing `/api/chat/stream` and `/api/chat/multimodal/stream` contracts remain the
application entry points. Legacy sessions keep the existing `AiClient.stream()` path. New
migrated sessions delegate final-answer generation to `StreamingAgentExecutionService`, so the
answer is produced by the existing Agent Runtime, Model Gateway, AgentRunner, persistence, and
coordination chain. Auxiliary intent, assessment, and temporary legacy RAG calls remain outside
the Runtime in this phase; they do not produce the student final answer.

## Feature flag and rollback

`multimodal-agent.runtime.chat-migration.enabled` defaults to `false`. When enabled, a request
without a session ID creates a Runtime-marked chat session. `chat_runtime_sessions` is the durable
routing marker: an existing marked session stays on Runtime even if the flag is later disabled,
while an unmarked legacy session stays on Legacy even if it is later enabled. A Runtime failure
is surfaced and never triggers a fallback model call or automatic fresh Run.

## Session identity

`ChatSession.publicId` is passed unchanged as `AgentRunSpec.sessionId`; every student request has
a distinct `runId` and request ID. The authenticated principal supplies `userId`. Both routing
and execution require the session to belong to that user, and the marker stores the same owner
identity. A cross-user continuation is rejected before admission.

## Business context projection

Privacy sanitization, intent classification, psychological assessment, multimodal fusion, and
the temporary legacy retrieval step run before fresh Runtime admission. Their trusted,
run-scoped model instructions are projected as plain SYSTEM messages by
`chat-business-context@1`. The current request remains exactly one plain USER message in
`request-messages@1`. The source is part of deterministic context assembly and immutable context
snapshot provenance; no ThreadLocal, global mutable prompt, or AgentRunner message injection is
used. Raw media bytes, internal scores, and unnecessary identifiers are excluded.

## Safety and risk handling

Explicit or fused HIGH risk overrides ordinary chat and Skill routing. The trusted safety prompt
requires immediate support and human-help guidance while prohibiting diagnosis and disclosure of
internal labels or scores. Psychological reports are saved and tool orchestration is triggered
before final-answer execution, so model failure does not erase the risk fact. HIGH-risk alert
attempts are independent of Excel reporting success, and an empty recipient configuration is
recorded as failure rather than notification success.

## SSE compatibility

The external protocol remains `meta`, zero or more `token`, and exactly one terminal `done` or
`error`. A fresh-execution lifecycle hook attaches the chat observer after the live stream is
opened but before model execution, closing the subscription race without replay, polling, or
delay. The adapter buffers a bounded model turn, discards tool-call and intermediate iterations,
and releases only the final STOP turn. Its visible text must equal durable `finalContent`.
`done` is emitted only after a COMPLETED result and successful history projection. A client
disconnect does not invoke Runtime cancellation.

## Chat history projection

The current USER message is retained in the legacy chat tables for product history. An ASSISTANT
message is inserted only after the associated AgentRun is durably COMPLETED. V10 adds
`chat_history_projections`, keyed by `run_id`, so repeated completion handling is idempotent and
identity checked. Assistant insertion and projection completion share one transaction. A
projection failure is recorded for explicit retry and does not execute the Run or model again.

## Durable memory ownership

`agent_runs` plus verified context snapshots remain the Runtime conversation-memory truth.
`ChatSession` and `ChatMessage` are compatibility views for students, administrators, and the
remaining auxiliary business classifiers. Runtime prompt assembly never reads Redis short-term
memory or injects legacy history. ADR-023 continues to reconstruct only the exact historical
`request-messages@1` USER message and durable final answer.

## Recovery isolation

Chat classification, business-context assembly, Skill resolution, memory loading, report
creation, and notifications occur only while preparing a fresh request. Recovery remains on the
existing P10 path and resumes the same Run exclusively from its durable checkpoint and snapshots.
It does not call the migration facade, recreate reports, reassemble business context, or repeat
external notifications.

## Known limitations

Intent classification, psychological assessment, and temporary `AgenticRagService` use may still
perform auxiliary model calls that are not governed by the Agent Runtime budget. P12 will own a
unified knowledge-retrieval design; this phase creates no new index or embedding pipeline.
Projection failures are durable and idempotently retryable, but automated projection repair is
not introduced. The live Runtime adapter intentionally buffers the bounded final model turn to
prevent intermediate or tool data leakage, so student tokens are released at durable completion
rather than immediately as provider deltas arrive.
