# ADR-018: Tool Recovery Contract

- Status: Accepted
- Date: 2026-09-28
- Phase: P10.3

## Context

Tool policy, tool risk, and crash-recovery semantics answer different questions. Approval controls whether an operation may begin; risk describes operational impact; neither proves whether a historical execution can be replayed or reconciled. Reading the current `ToolDescriptor` during recovery is also unsafe because deployed tool semantics can drift after an execution was recorded.

## Decision

Each descriptor declares a versioned `ToolRecoveryContract` with two orthogonal dimensions: replay semantics (`REPLAY_SAFE`, `IDEMPOTENT`, or `NON_REPLAYABLE`) and reconciliation support (`SUPPORTED` or `UNSUPPORTED`). Reconciliation support is never inferred and requires a stable strategy identity when supported. `ToolRecoveryClass` is only a diagnostic projection; the complete contract remains authoritative.

Existing descriptor construction remains compatible. Read-only tools derive `REPLAY_SAFE`; side-effecting idempotent tools derive `IDEMPOTENT`; other side-effecting tools derive `NON_REPLAYABLE`. Descriptor flags and declared replay semantics must agree. Idempotence does not imply that an external idempotency-key protocol exists.

Before a permitted tool can emit `TOOL_STARTED` or call its external implementation, middleware persists a deterministic `ToolRecoveryContractSnapshot` against the concrete `runId + toolCallId`. Its SHA-256 identity covers the tool name, contract version, replay semantics, reconciliation support, strategy identity, and schema version. Persistence uses an independent short transaction. Rebinding an identical snapshot is idempotent; attempting to replace it with a different snapshot is a hard conflict.

Historical recovery reads the execution-time binding from `tool_executions`, never the current registry. Pre-P10.3 rows remain nullable and readable. Missing historical contracts are reported as `CONTRACT_UNAVAILABLE`; they are not backfilled or guessed. P10.2 continues to classify `STARTED` and `UNKNOWN` as ambiguous regardless of the bound capability.

## Consequences

- Contract binding failure stops dispatch before `TOOL_STARTED` and external execution.
- Binding transactions never remain open across external tool calls.
- A persisted `idempotency_key` is not evidence that the original external request used it.
- P10.3 classifies and persists capability only. It does not replay, reconcile, resume, materialize `UNKNOWN`, repair durable status, or acquire recovery ownership.
- Reconciliation execution, reliable side-effect identity propagation, budget reconstruction, and resume orchestration remain deferred to P10.4 through P10.6.
