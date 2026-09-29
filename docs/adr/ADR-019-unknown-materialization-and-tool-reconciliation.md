# ADR-019: UNKNOWN Materialization and Tool Reconciliation

- Status: Accepted
- Date: 2026-09-29
- Phase: P10.4

## Context

A durable `STARTED` tool execution has crossed `TOOL_STARTED`, but a crash may prevent the Runtime from recording whether the external operation completed. Neither the current tool descriptor nor a retry can safely resolve that ambiguity. P10.3 preserves the execution-time recovery contract, including any explicit reconciliation strategy identity.

## Decision

`UNKNOWN` is first-class durable truth. Under explicitly asserted recovery authority, `STARTED` may become `UNKNOWN`; repeating that transition is idempotent. `PLANNED` is not ambiguous, and terminal tool truth is never downgraded. The AgentRun is unchanged and its Tool step remains `RUNNING`.

Reconciliation is an observational query selected only by the persisted P10.3 `reconciliationStrategyId`. It must not invoke the original `AgentTool`, replay a side effect, compensate an operation, or infer a strategy from the current registry. `CONFIRMED_APPLIED`, `CONFIRMED_NOT_APPLIED`, `UNRESOLVED`, and `MANUAL_INTERVENTION_REQUIRED` describe external evidence; none is a Runtime `SUCCEEDED` or `FAILED` fact.

Each external observation has a durable attempt. One short transaction commits `STARTED`, the reconciler runs with no database transaction, and another short transaction records `COMPLETED`, `FAILED`, or `SUPERSEDED`. Attempt numbers are allocated while holding a pessimistic lock on the ToolExecution. A decisive completed attempt is reused, while `UNRESOLVED` and failed attempts may be retried. An existing `STARTED` attempt prevents concurrent duplicate observation.

Normal P6 terminal facts remain authoritative. If `TOOL_SUCCEEDED` or `TOOL_FAILED` arrives while reconciliation is running, the terminal ToolExecution wins and the reconciliation attempt becomes `SUPERSEDED`. Reconciler or result-persistence failure leaves the ToolExecution `UNKNOWN` and never fails the AgentRun.

## Consequences

- P10.2 continues to classify `STARTED` and `UNKNOWN` as `REQUIRES_RECONCILIATION`.
- Missing contracts and missing reconcilers fail closed; the current descriptor is never a fallback.
- `REPLAY_DEFERRED` and `IDEMPOTENT_RETRY_DEFERRED` are plans only.
- P10.4 resolves ambiguity only. Reliable ToolResult materialization, budget recovery, idempotency-key propagation, automatic replay, recovery ownership acquisition, startup scanning, and run resume remain deferred to P10.5 and P10.6.
