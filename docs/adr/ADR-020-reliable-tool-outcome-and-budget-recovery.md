# ADR-020: Reliable Tool Outcome and Budget Recovery

- Status: Accepted
- Date: 2026-09-29
- Phase: P10.5

## Context

A tool can complete and produce its exact model-visible result before the Runtime appends that result to conversation state. Persisting `TOOL_SUCCEEDED` first creates a crash window in which lifecycle history says the tool succeeded but recovery has only a lossy summary, or no usable result at all. Separately, calls admitted after the latest checkpoint have already consumed budget even when a crash prevents their usage from reaching the checkpoint.

P10.4 reconciliation can prove that an external side effect occurred. That observation does not reconstruct the exact payload that the model would have received and therefore cannot, by itself, establish resumable successful Tool truth.

## Decision

Every newly successful durable tool execution records one immutable `ReliableToolOutcome` before emitting `TOOL_SUCCEEDED`. It contains the execution, run, call, and tool identities; the exact model-visible result payload; its SHA-256 hash; recording time; and schema version. One ToolExecution has at most one outcome. Retrying the same exact outcome is idempotent; a different identity or payload is a hard conflict. Outcome payloads are recovery data and must not be copied into ordinary logs.

The Runtime depends only on `ToolOutcomeRecorder`. Production supplies a JPA adapter; legacy and isolated execution paths use a NOOP recorder. The success order is `TOOL_STARTED`, external execution, exact serialization, durable outcome recording, `TOOL_SUCCEEDED`, then returning `ToolResult`. A recording failure emits no success or failure terminal event and leaves ToolExecution `STARTED`, so existing ambiguity handling remains authoritative.

P10.5H distinguishes Tool execution failure from every post-execution outcome failure. A Tool implementation that returned successfully is not reported as `TOOL_FAILED` merely because output serialization or reliable-outcome recording failed. Execution exceptions retain the existing `TOOL_FAILED` contract; serialization raises `ToolOutcomeSerializationException`; reliable-outcome persistence raises `ToolOutcomeRecordingException`; and a P6 projection failure after the Core emitted `TOOL_SUCCEEDED` preserves that success fact. None of the post-execution failures fabricates a `ToolResult` or a terminal Tool failure.

Under a valid `RecoveryAuthorityGuard`, a `STARTED` or `UNKNOWN` ToolExecution with a matching exact outcome may be materialized as `SUCCEEDED`, together with its Tool AgentStep. This repair does not modify AgentRun terminal state. Repetition is idempotent. `FAILED`, `BLOCKED`, `CANCELLED`, inconsistent step truth, or mismatched identity fail closed. A historical execution without an outcome remains readable but returns `RELIABLE_OUTCOME_UNAVAILABLE`. In particular, `CONFIRMED_APPLIED`, `externalReference`, and `evidenceSummary` can never synthesize a ToolResult.

After successful materialization, recovery may append the exact tool-result message to a new `AFTER_TOOL_OUTCOME` checkpoint. It preserves seen and approved tool-call identities and the original configuration snapshot, updates tools used, carries reconstructed budget, and uses the next monotonic sequence. This creates continuation state only; it does not resume the run or execute a tool.

Budget recovery starts with the latest checkpoint and counts durable model and tool work not yet represented there. `RUNNING`, `SUCCEEDED`, or `FAILED` model attempts consume one model call. `STARTED`, `UNKNOWN`, `SUCCEEDED`, or `FAILED` tool attempts consume one tool call. Each durable identity is counted once and checkpoint counters never decrease. Because current durable model facts do not contain exact token usage, a post-checkpoint model attempt sets `unknownUsageObserved`; unknown tokens are never converted to zero and cost becomes unavailable rather than estimated.

`BudgetSession.restore` rehydrates the recovered immutable `BudgetUsage`. Existing call, token, and cost consumption remains enforceable. Unknown token usage with token limits, or unavailable cost with a cost limit, blocks future work as `BUDGET_UNVERIFIABLE`.

## Consequences

- `ToolExecution = SUCCEEDED` for new executions requires an exact durable outcome recorded first.
- A crash after outcome recording but before `TOOL_SUCCEEDED` is repairable without replay.
- P10.2 eligibility and P10.4 reconciliation history remain unchanged.
- Historical successes without outcomes fail closed only when exact recovery is requested.
- P10.5 does not add automatic replay, compensation, Saga/2PC, startup scanning, lease acquisition, AgentRunner resume, or any second execution loop. Those concerns remain deferred.
