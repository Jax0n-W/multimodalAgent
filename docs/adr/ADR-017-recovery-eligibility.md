# ADR-017: Recovery Eligibility

- Status: Accepted
- Date: 2026-09-28
- Phase: P10.2

## Context

P6 durable execution history records confirmed run, model, and tool facts. P10.1 recovery checkpoints record exact continuation state at safe boundaries. Neither source alone answers whether a historical run can be resumed without contradicting durable truth, duplicating confirmed work, or ignoring an uncertain external side effect.

## Decision

Recovery eligibility is a read-only classification, not recovery execution. A JPA evidence reader loads the `AgentRun`, ordered step and tool history, and latest checkpoint in one read transaction, then converts them to immutable framework-neutral evidence. A pure Java evaluator returns an immutable `RecoveryDecision`; it does not resume, retry, reconcile, acquire a lease, write a checkpoint, or mutate durable state.

The decision dispositions are:

- `SAFE_TO_RESUME`: durable continuation state is sufficient and no known semantic blocker exists. This is durable-semantic eligibility only; it is not execution permission and does not imply P7 ownership.
- `REQUIRES_RECONCILIATION`: a `STARTED` or `UNKNOWN` tool outcome can have an unresolved external side effect.
- `NOT_RESUMABLE`: current evidence cannot be continued safely under implemented recovery semantics.
- `MANUAL_INTERVENTION`: a coherent `WAITING_APPROVAL` run is intentionally paused for external input.

Classification precedence is stable: missing run, terminal truth, never-started run, evidence inconsistency, waiting approval, missing checkpoint, ambiguous tool execution, confirmed facts not covered by the checkpoint, in-flight model attempt, unresolved model control state, then eligibility. Repository return order is not classification input; diagnostics are sorted by stable identities.

Terminal truth is never recoverable. A `CREATED` run is not crash recovery. A running run without a checkpoint cannot be reconstructed from execution history alone. A waiting-approval run requires a matching `WAITING_APPROVAL` checkpoint.

A coherent `WAITING_APPROVAL` state cannot contain `STARTED` or `UNKNOWN` tool execution evidence. If it does, the approval state contradicts durable tool history and fails closed as `INCONSISTENT_TOOL_HISTORY`; approval classification must never hide possible or already-started side effects. A represented `PLANNED` tool remains compatible with approval waiting because it has not crossed `TOOL_STARTED`.

`PLANNED` tool work represented by the checkpoint has not crossed `TOOL_STARTED` and does not itself imply side-effect ambiguity. `STARTED` and `UNKNOWN` require reconciliation without consulting tool replay policy. Confirmed `SUCCEEDED`, `FAILED`, `BLOCKED`, or `CANCELLED` facts require an exact checkpoint tool-result message for the same call identity and tool name; result summaries are not continuation state.

An in-flight model attempt is distinct from tool reconciliation. It fails closed because model-call budget and provider usage may not be represented by the checkpoint. Confirmed model outcomes newer than the checkpoint cannot be silently re-executed. When an `AFTER_MODEL_OUTCOME` checkpoint contains a plain assistant result but cannot distinguish `STOP` from `LENGTH`, V1 returns `NOT_RESUMABLE` rather than guessing control flow.

P10.6 must acquire P7 ownership, re-read durable evidence, and re-evaluate eligibility before any future resume operation. This handles the time-of-check/time-of-use boundary without adding a mutating lease check to P10.2.

## Consequences

- No persistence schema or recovery-decision table is added.
- Checkpoint, run, snapshot, model, and tool contradictions fail closed with typed reasons.
- `UNKNOWN` remains distinct from success and failure and is never materialized by this phase.
- Tool recovery policy, reconciliation lifecycle, reliable tool outcome and budget reconstruction, and the resume engine remain deferred to P10.3 through P10.6.
