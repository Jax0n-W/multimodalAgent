# ADR-016: Durable Recovery Checkpoint

- Status: Accepted
- Date: 2026-09-28
- Phase: P10.1

## Context

`AgentRun`, `AgentStep`, and `ToolExecution` are durable execution history. They record confirmed facts, but they do not contain the exact conversation, duplicate-tool-call guard, approvals, or complete budget state needed to continue an interrupted Agent loop. Durable history is therefore not recovery state, and reconstructing continuation state ad hoc from those projections would be incomplete.

## Decision

P10.1 introduces `RecoveryCheckpoint` as a distinct, immutable, append-only source of continuation state. Each checkpoint contains the exact `AgentMessage` list, ordered tools used, seen and approved tool-call IDs, immutable budget usage, the original P9.3 runtime configuration snapshot identity, iteration, boundary, creation time, deterministic identity, and schema version.

Only confirmed safe boundaries create checkpoints: `AFTER_MODEL_OUTCOME`, `AFTER_TOOL_OUTCOME`, `ITERATION_BOUNDARY`, and `WAITING_APPROVAL`. `MODEL_STARTED` and `TOOL_STARTED` consume their P9.2 call budgets but are not safe checkpoints because their outcomes are not known. Later recovery policy must reconcile such in-flight history; P10.1 does not reinterpret it as a resumable state.

Checkpoint capture uses the existing pure-Java Runtime middleware seam. It receives the exact `ModelTurn`, exact `ToolResult`, and final `AgentRunResult`, mirrors the Runtime continuation state, and writes only after the existing synchronous P6 projection has recorded the corresponding terminal fact. JPA, Jackson, Flyway, and Spring remain outside `runtime/**`.

The V4 table stores one row per checkpoint with a deterministic checkpoint ID and monotonically increasing run-local sequence. A repeated write of identical content is idempotent. Reusing an identity with different content fails. Rows are never updated. Reads accept only the current schema and validate JSON, domain invariants, the referenced run, and the original runtime configuration snapshot. Unsupported or corrupt state fails closed.

Budget state records model and tool calls, input/output/total tokens, cost when knowable, and whether unknown usage was observed. It does not serialize `BudgetSession`. Started work remains consumed under P9.2 semantics, and unknown usage remains distinct from zero.

## Consequences

- Durable execution history and durable recovery state remain separate truths with different purposes.
- Exact assistant tool calls and exact tool-result messages survive a persistence round trip.
- A checkpoint preserves the original execution configuration rather than resolving current application configuration.
- Checkpoint persistence does not decide whether recovery is safe. Recovery eligibility belongs to P10.2.
- P10.1 does not scan, retry, reconcile, acquire recovery ownership, or restart `AgentRunner`. The resume engine belongs to P10.6.
