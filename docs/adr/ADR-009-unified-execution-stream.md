# ADR-009: Unified Execution Stream

## Status

Accepted for P8.3.

## Decision

The execution stream is a node-local, live projection of execution activity. It is not Runtime
truth and it is not durable history. Runtime facts remain `AgentEvent` values; streaming wraps the
same fact in `RuntimeEventPayload` without changing it.

One open `runId` has exactly one `streamSequence` authority. Runtime events, model deltas, and
control observations all enter `ExecutionStreamPublisher`; individual producers never allocate a
sequence. `RunStreamState` serializes sequence allocation, envelope creation, and subscriber
enqueue as one publication boundary. Consequently, delivery order for every subscriber is the
same as `streamSequence` order even when producers are concurrent.

`ExecutionStreamEvent.occurredAt` is the time the payload enters that unified publication
boundary. A wrapped `AgentEvent` retains its original `occurredAt` and `sequence`. Runtime
`AgentEvent.sequence` and live `streamSequence` are intentionally separate orderings.

Each subscriber owns a bounded queue and an asynchronous delivery path. A queue overflow
disconnects only that slow subscriber; events are not silently dropped and publication never
waits for an SSE client. Subscriber callbacks, transport failures, serialization failures, and
disconnects are observation failures only and cannot synthesize or rewrite Runtime terminal
events or `AgentRunResult`.

Subscriptions are live-only: they observe events published after subscription. P8.3 does not
replay `Last-Event-ID`, query MySQL for history, or persist `streamSequence`. Explicit outer
execution lifecycle opens the run stream before execution and closes it after the outer execution
returns or throws; terminal Runtime events alone do not own stream cleanup.

For P8.3, a closed durable `runId` is not reopened into a second live stream generation.
`streamSequence` is unique within the single live-stream lifecycle associated with that durable
run execution. Production composition opens the Hub only in the Runtime-context contributor,
which runs after P6's unique durable admission and before `RUN_STARTED`. A repeated `runId`
therefore fails admission before Hub opening. The ephemeral Hub itself does not retain closed IDs;
the durable run uniqueness constraint supplies the executable guard without an unbounded in-memory
tombstone set. P7 lease acquisition, P6 admission, and their caller-visible failure precedence
remain inside the existing coordinators.

The separate opt-in `/api/agent/runs` production entry composes the P8.2 streaming model,
P8.3 bridges, P6 persistence, and optional P7 coordination. It closes the Hub only after the
complete outer execution returns or throws. Existing `/api/chat` remains on its legacy path.
Clients must supply the unique `runId` and connect to SSE concurrently with the execution
request; because observation is live-only, connecting after execution starts may miss early
events including `RUN_STARTED`. The SSE route is `/api/agent/runs/{runId}/stream` and inherits
the application's authenticated `/api/**` boundary.

An SSE disconnect only removes that subscription. It does not request cancellation, stop an
`AgentRunner`, mutate a lease, or alter execution-control state.

## Deployment limitation

P8.3 live streaming is node-local. Until distributed stream routing exists, deployments require
ingress affinity or owner-aware routing so the SSE request reaches the node executing the run.
Redis coordination and future distributed cancellation do not provide distributed live-stream
forwarding.

## Deferred

Cancellation wiring and endpoints (P8.4), distributed control (P8.5), replay, recovery, durable
stream storage, Redis Pub/Sub/Streams, Kafka, and model-gateway work are outside P8.3.
