# ADR-029: Single Unified Chat Runtime

- Status: Accepted
- Date: 2026-10-15
- Supersedes: ADR-025 chat-path feature-flag routing

## Decision

Both `/api/chat/stream` and `/api/chat/multimodal/stream` always execute through the Unified Agent
Runtime. There is no legacy final-answer route, no Runtime enable switch, and no chat-migration
switch. Runtime conversation memory is part of the mandatory execution path; configuration may
tune its bounds or enable optional capabilities such as Skills, but it must not select a second
execution engine.

`ChatService` remains the stable controller-facing boundary. It delegates every request to
`UnifiedChatRuntimeService`, which retains the existing campus business sequence: authenticated
session resolution, privacy sanitization, intent and risk assessment, durable user/report/outbox
writes, safe retrieval, immutable context assembly, Runtime execution, and idempotent projection
back into the existing chat history tables.

## Session model

The application has not been released, so there is no legacy-session population to migrate. It does
not create a `chat_runtime_sessions` marker or maintain a cutover timestamp. `chat_sessions` remains
the product conversation identity, while every turn is represented by the Runtime's durable
`agent_runs`, snapshots, checkpoints, and conversation memory. An unknown or cross-user session ID
is rejected instead of being adopted or rerouted.

## Failure semantics

A Runtime failure is returned as a terminal safe error. The application never invokes the removed
legacy model path as fallback, because that would create duplicate side effects and split audit,
budget, recovery, and safety truth across two engines.

Retrieval-mode degradation remains an independent capability decision inside the single Runtime
business pipeline; it is not a chat execution route.
