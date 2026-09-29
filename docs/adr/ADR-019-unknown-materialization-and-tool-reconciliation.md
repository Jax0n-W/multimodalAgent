# ADR-019: UNKNOWN Materialization and Tool Reconciliation

- Status: Accepted
- Date: 2026-09-29
- Phase: P10.4

## Context

A durable `STARTED` tool execution has crossed `TOOL_STARTED`, but a crash may prevent the Runtime from recording whether the external operation completed. Neither the current tool descriptor nor a retry can safely resolve that ambiguity. P10.3 preserves the execution-time recovery contract, including any explicit reconciliation strategy identity.

## Decision

`UNKNOWN` is first-class durable ambiguity, not failure. Under explicitly asserted recovery authority, `STARTED` may become `UNKNOWN`; repeating that transition is idempotent. `PLANNED` is not ambiguous, and terminal tool truth is never downgraded. The AgentRun is unchanged and its Tool step remains `RUNNING`.

Reconciliation is an observational query selected only by the persisted P10.3 `reconciliationStrategyId`. The strategy ID is a stable protocol identity; incompatible reconciliation behavior requires a new identity, such as `payment-lookup-v2` instead of changing `payment-lookup-v1`. Reconciliation must not invoke the original `AgentTool`, replay a side effect, compensate an operation, or infer a strategy from the current descriptor, tool name, or ToolRegistry. `CONFIRMED_APPLIED`, `CONFIRMED_NOT_APPLIED`, `UNRESOLVED`, and `MANUAL_INTERVENTION_REQUIRED` describe external evidence; none is Runtime lifecycle truth or a `SUCCEEDED` or `FAILED` fact.

Each external observation has a durable attempt. One short transaction commits `STARTED`, the reconciler runs with no database transaction, and another short transaction records `COMPLETED`, `FAILED`, or `SUPERSEDED`. TX2 always re-reads the pessimistically locked ToolExecution before recording completion. Attempt numbers are allocated while holding that same lock. A decisive completed attempt is reused only while ToolExecution remains `UNKNOWN`; `UNRESOLVED` and failed attempts may be retried.

A durable `STARTED` reconciliation attempt may survive a crash. P10.4 cannot distinguish a live attempt from an orphan without recovery ownership, so it never reclaims an attempt by `startedAt`, `createdAt`, age, or timeout. An existing `STARTED` attempt means `IN_PROGRESS` and prevents attempt N+1. Orphan takeover is deferred.

Normal P6 terminal facts remain authoritative. Terminal ToolExecution truth outranks both an earlier reconciliation plan and historical decisive reconciliation observations. A terminal transition between UNKNOWN materialization and attempt allocation produces `NOT_AMBIGUOUS` with no stale plan. If `TOOL_SUCCEEDED` or `TOOL_FAILED` arrives while reconciliation is running, TX2 observes it and marks the attempt `SUPERSEDED`; the terminal ToolExecution remains unchanged. A later resolve also returns `NOT_AMBIGUOUS`, never a historical `REUSED` result.

`RecoveryAuthorityGuard` validates authority already established by a higher-level recovery coordinator; it does not acquire ownership. Authority is revalidated around recovery mutation phases. Once validation fails, P10.4 fails closed and performs no further recovery mutation. In particular, authority failure is not converted into attempt `FAILED`.

Pessimistic ToolExecution locks order durable mutations, including P6 terminal persistence versus reconciliation completion. They are neither recovery ownership nor execution fencing, cannot prevent an already-issued external side effect, and do not authorize recovery work.

Reconciler failure or result-persistence failure leaves ToolExecution `UNKNOWN` and never fails the AgentRun. Recording attempt `FAILED` is best effort and is allowed only while authority remains valid. If authority is lost or both completion and failure persistence fail, the attempt may legitimately remain `STARTED`.

## Consequences

- P10.2 continues to classify `STARTED` and `UNKNOWN` as `REQUIRES_RECONCILIATION`.
- Missing contracts and missing reconcilers fail closed; the current descriptor is never a fallback.
- P10.4 neither establishes ownership nor reclaims dangling `STARTED` attempts.
- `REPLAY_DEFERRED` and `IDEMPOTENT_RETRY_DEFERRED` are plans only.
- P10.4 resolves ambiguity only. Reliable ToolResult materialization, budget recovery, idempotency-key propagation, automatic replay, recovery ownership acquisition, startup scanning, and run resume remain deferred to P10.5 and P10.6.
