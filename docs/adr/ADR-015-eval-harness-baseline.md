# ADR-015: Eval Harness and Runtime Baseline

- Status: Accepted
- Date: 2026-09-28
- Phase: P9.4

## Context

The Runtime needs repeatable evidence about execution contracts before later quality work. Existing observability already exposes stop reasons, model and tool events, token usage, typed provider failures, and the P9.3 execution-configuration snapshot identity. Reimplementing the Agent loop for evaluation would create a second source of execution semantics.

## Decision

P9.4 adds a thin evaluation layer:

`EvalSuite -> EvalRunner -> EvalExecutionTarget -> existing execution -> EvalObservation -> EvalRecord -> metrics -> JSON/Markdown`

`EvalExecutionTarget` is only an adapter boundary. The Runtime target invokes the existing coordinator and observes its result, events, model telemetry, snapshot identity, and elapsed time. It does not reproduce routing, tool execution, budgets, or stop-reason mapping. A future legacy target may implement the same boundary, but no legacy evaluator is implemented in this phase.

The checked-in suite is versioned synthetic data. A case declares a deterministic contract: expected stop reason, allowed/expected/forbidden tools, and optional model/tool call bounds. `contractPass` means those observable constraints passed. It is explicitly not semantic answer correctness or task success.

Default Maven tests exclude the `eval-real` tag and make no external provider calls. The `eval-real` profile is opt-in and may produce `target/eval/eval-results.json`, `eval-summary.json`, and `eval-report.md`. Real runs are descriptive baselines: P9.4 defines deltas but no nondeterministic pass/fail threshold.

Reproducibility metadata records the Git SHA, explicitly injected source-tree state, suite identity/version, generation time, target, runtime snapshot identities, and model identities. A Git SHA identifies only committed source. When the working tree is dirty, HEAD does not fully identify the Eval harness, dataset, metrics, comparator, or report generator that actually ran. Eval does not inspect Git, hash the source tree, or reconstruct provenance; the baseline runner receives `CLEAN`, `DIRTY`, or `UNKNOWN` through external configuration.

`fullyReproducible` is true only when the Git SHA is present, the injected source-tree state is `CLEAN`, suite ID/version are present, at least one actual runtime snapshot identity is present with none missing, and model identity is present. Missing or dirty provenance is reported explicitly rather than inferred from a SHA.

Baseline comparison first requires identical suite ID, suite version, and case-ID set. Case order does not matter. An incompatible dataset produces the typed `INCOMPATIBLE_BASELINE` failure and no deltas. Different Git revisions, model identities, runtime snapshots, token usage, costs, and latency remain intentionally comparable when the dataset contract is identical.

Incomplete token usage is represented as unknown, never as zero. Cost is reported only when both complete usage and explicit matching pricing are available. Latency percentiles use the nearest-rank method: sort ascending and select one-based rank `ceil(p * N)`.

## Consequences

- Evaluation exercises the production execution semantics without introducing another Agent loop.
- CI remains deterministic and offline by default.
- Real-model results can vary and must be interpreted as observed baseline data.
- A dirty-tree real run is a development artifact, not a formal reproducible baseline.
- P11 product/semantic claims and P12 threshold policy remain out of scope.
