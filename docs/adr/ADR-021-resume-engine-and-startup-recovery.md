# ADR-021: Resume Engine and Startup Recovery

- Status: Accepted
- Date: 2026-09-29
- Phase: P10.6

## Context

P10.1–P10.5 establish durable checkpoints, eligibility classification, historical tool recovery contracts, ambiguity reconciliation, exact reliable outcomes, and budget reconstruction. They intentionally stop before orchestration. A crashed `RUNNING` execution therefore still needs one owner to validate all durable facts, repair only provable tool state, restore the original execution contract, and continue from the precise checkpoint control position.

Sending such work through the fresh chain would create a second admission, snapshot current configuration, reset budget accounting, and emit another run start. Resuming from messages alone would also be incorrect: an `AFTER_MODEL_OUTCOME` checkpoint can contain pending tool calls that must execute before another model call.

## Decision

Recovery uses a separate `RecoveryEngine` and `RecoveryExecutionRequest`. It never enters `SnapshottingAgentExecutionCoordinator` and `PersistentAgentExecutionCoordinator.resumeExisting` validates and finalizes the existing `AgentRun` without admission. The durable `runId`, session, snapshot link, and request history remain unchanged, and `AgentRunner.resume` emits no `RUN_STARTED`.

Every recovery attempt first acquires the existing P7 run lease and starts its watchdog. `AlreadyActive` is a no-op; unavailable coordination fails closed. Only after acquisition does the engine reread durable evidence and run `RecoveryEligibilityEvaluator`, preventing scan-time decisions from crossing the ownership boundary. The lease session is used both as the P10 recovery authority guard and as the existing Runtime cooperative fencing source. Lease loss prevents subsequent repair, model, tool, checkpoint, or finalization work.

Ambiguous tools first consult exact `ReliableToolOutcome` truth. An exact outcome may materialize durable success and advance the checkpoint before evidence is reread. Reconciliation summaries never synthesize model-visible results. `CONFIRMED_APPLIED` without an exact result is manual. A deferred retry is allowed only for the persisted historical `REPLAY_SAFE` or `IDEMPOTENT` contract, uses the same logical `toolCallId`, and consumes an additional restored tool-call budget unit. `NON_REPLAYABLE` is never automatically retried, including after `CONFIRMED_NOT_APPLIED`. A newly acquired P7 owner may mark an orphan `STARTED` reconciliation attempt `ABANDONED`; age and timeout are not takeover authority.

The original P9.3 snapshot referenced by `AgentRun` is authoritative. Recovery verifies its canonical JSON, SHA-256 hash, and identity, then restores maximum iterations, allowed tools, budget, and model configuration. It does not invoke the current configuration resolver. A Runtime whose complete model configuration differs from the historical configuration fails closed.

`AgentResumeState` rehydrates checkpoint messages, tools used, seen/requested call identities, pending calls, current iteration, checkpoint sequence, and reconstructed budget. The checkpoint session starts at the persisted sequence, so all new checkpoints remain monotonic. Pending calls from `AFTER_MODEL_OUTCOME` or a partial `AFTER_TOOL_OUTCOME` execute before the next model turn; completed calls are excluded. With no pending calls, continuation starts at the next model iteration. `WAITING_APPROVAL` remains manual.

A startup `RecoveryScanner` queries durable `RUNNING` runs only and submits each to the complete acquire/re-read/evaluate protocol. It introduces no second distributed lock. Fresh execution retains its existing Snapshotting, P7 coordination, checkpointing, persistence, and `AgentRunner.run` semantics.

Each actual recovery execution segment enters the same node-local control and streaming shell as fresh execution, but only after P7 acquisition and P6 validation of the existing Run. The shell installs the real cancellation context, opens the one segment-local `ExecutionStreamHub` state, and registers the Model Delta observer. Runtime facts, deltas, and control observations therefore share one live sequence for that segment. All initialized resources are identity-safely removed on every exit path. This lifecycle does not perform admission, does not create another Run, and does not emit another `RUN_STARTED`.

## Consequences

- Resume continues the same durable run and normally finalizes that existing record.
- Original configuration and recovered consumption govern all future work.
- Exact outcomes repair crash windows without replay; ambiguous non-replayable work remains manual.
- Recovery uses the existing Runtime model/tool loop and P7 fencing rather than a workflow engine, Saga, 2PC, compensation system, or provider failover mechanism.
- P11 memory/context and P12 RAG remain outside this decision.
